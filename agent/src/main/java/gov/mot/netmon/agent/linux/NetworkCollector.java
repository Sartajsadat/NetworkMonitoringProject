package gov.mot.netmon.agent.linux;

import java.io.IOException;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.Rates;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.model.Report;

/** Interface state and counters from /sys/class/net; rates from deltas between reports. */
public class NetworkCollector implements Collector {

    /** IP addresses with prefix length (e.g. 10.0.0.5/24) for an interface. */
    public interface AddressProvider {
        List<String> addresses(String iface);
    }

    record Counters(Long rxBytes, Long txBytes, Long rxPackets, Long txPackets, Long rxErrors, Long txErrors,
            Long rxDropped, Long txDropped) {
    }

    private final Path sys;
    private final AddressProvider addresses;
    private Map<String, Counters> previous;
    private Instant previousAt;

    public NetworkCollector(Path sys) {
        this(sys, NetworkCollector::javaAddresses);
    }

    public NetworkCollector(Path sys, AddressProvider addresses) {
        this.sys = sys;
        this.addresses = addresses;
    }

    @Override
    public String name() {
        return "network";
    }

    @Override
    public void collect(ReportBuilder b, Instant now) throws Exception {
        Path base = sys.resolve("class/net");
        TreeSet<String> names = new TreeSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(base)) {
            ds.forEach(p -> names.add(p.getFileName().toString()));
        }
        double secs = previousAt == null ? 0 : (now.toEpochMilli() - previousAt.toEpochMilli()) / 1000.0;
        Map<String, Counters> current = new HashMap<>();
        List<Report.NetworkInterface> out = new ArrayList<>();
        for (String name : names) {
            if (out.size() >= 64) break;
            Path dir = base.resolve(name);
            Path st = dir.resolve("statistics");
            Counters c = new Counters(ProcFs.longOrNull(st.resolve("rx_bytes")), ProcFs.longOrNull(st.resolve("tx_bytes")),
                    ProcFs.longOrNull(st.resolve("rx_packets")), ProcFs.longOrNull(st.resolve("tx_packets")),
                    ProcFs.longOrNull(st.resolve("rx_errors")), ProcFs.longOrNull(st.resolve("tx_errors")),
                    ProcFs.longOrNull(st.resolve("rx_dropped")), ProcFs.longOrNull(st.resolve("tx_dropped")));
            current.put(name, c);
            Counters p = previous == null ? null : previous.get(name);
            Double rxBps = null, txBps = null, rxPps = null, txPps = null, errPs = null, dropPs = null;
            if (p != null) {
                Double rxB = Rates.perSecond(p.rxBytes(), c.rxBytes(), secs);
                Double txB = Rates.perSecond(p.txBytes(), c.txBytes(), secs);
                rxBps = rxB == null ? null : Rates.round(rxB * 8, 1);
                txBps = txB == null ? null : Rates.round(txB * 8, 1);
                rxPps = Rates.round(Rates.perSecond(p.rxPackets(), c.rxPackets(), secs), 2);
                txPps = Rates.round(Rates.perSecond(p.txPackets(), c.txPackets(), secs), 2);
                errPs = Rates.round(sum(Rates.perSecond(p.rxErrors(), c.rxErrors(), secs),
                        Rates.perSecond(p.txErrors(), c.txErrors(), secs)), 3);
                dropPs = Rates.round(sum(Rates.perSecond(p.rxDropped(), c.rxDropped(), secs),
                        Rates.perSecond(p.txDropped(), c.txDropped(), secs)), 3);
            }
            Long speed = ProcFs.longOrNull(dir.resolve("speed"));
            String mac = ProcFs.stringOrNull(dir.resolve("address"));
            Long mtu = ProcFs.longOrNull(dir.resolve("mtu"));
            out.add(new Report.NetworkInterface(name, ProcFs.stringOrNull(dir.resolve("operstate")),
                    mac == null || mac.equals("00:00:00:00:00:00") ? null : mac, addresses.addresses(name),
                    speed == null || speed <= 0 ? null : speed, mtu == null ? null : mtu.intValue(),
                    c.rxBytes(), c.txBytes(), c.rxPackets(), c.txPackets(), c.rxErrors(), c.txErrors(), c.rxDropped(),
                    c.txDropped(), rxBps, txBps, rxPps, txPps, errPs, dropPs));
        }
        previous = current;
        previousAt = now;
        b.network = out;
    }

    private static Double sum(Double a, Double b) {
        return a == null || b == null ? null : a + b;
    }

    static List<String> javaAddresses(String iface) {
        try {
            NetworkInterface ni = NetworkInterface.getByName(iface);
            if (ni == null) return List.of();
            List<String> out = new ArrayList<>();
            for (InterfaceAddress a : ni.getInterfaceAddresses()) {
                String host = a.getAddress().getHostAddress();
                int pct = host.indexOf('%');
                out.add((pct > 0 ? host.substring(0, pct) : host) + "/" + a.getNetworkPrefixLength());
                if (out.size() >= 32) break;
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }
}
