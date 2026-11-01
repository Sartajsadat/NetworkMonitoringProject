package gov.mot.netmon.agent.windows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.CollectorException;
import gov.mot.netmon.agent.collect.Rates;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.model.Report;

/** Adapters (Get-NetAdapter), cumulative statistics (Get-NetAdapterStatistics) and IPs (Get-NetIPAddress). */
public class WinNetworkCollector implements Collector {

    record Counters(Long rxBytes, Long txBytes, Long rxPackets, Long txPackets, Long rxErrors, Long txErrors,
            Long rxDropped, Long txDropped) {
    }

    private Map<String, Counters> previous;
    private Instant previousAt;

    @Override
    public String name() {
        return "network";
    }

    @Override
    public void collect(ReportBuilder b, Instant now) throws Exception {
        List<JsonNode> adapters = WinData.section(b, "adapters");
        Map<String, JsonNode> stats = new HashMap<>();
        try {
            for (JsonNode s : WinData.section(b, "adapterStats")) stats.put(WinData.str(s, "Name"), s);
        } catch (CollectorException e) {
            // counters unavailable: adapters are still listed, without traffic values
        }
        Map<String, List<String>> ips = new HashMap<>();
        try {
            for (JsonNode a : WinData.section(b, "addresses")) {
                String alias = WinData.str(a, "InterfaceAlias");
                String ip = WinData.str(a, "IPAddress");
                Long prefix = WinData.lng(a, "PrefixLength");
                if (alias != null && ip != null) {
                    int pct = ip.indexOf('%');
                    ips.computeIfAbsent(alias, k -> new ArrayList<>()).add((pct > 0 ? ip.substring(0, pct) : ip)
                            + (prefix == null ? "" : "/" + prefix));
                }
            }
        } catch (CollectorException e) {
            // addresses unavailable
        }
        double secs = previousAt == null ? 0 : (now.toEpochMilli() - previousAt.toEpochMilli()) / 1000.0;
        Map<String, Counters> current = new HashMap<>();
        List<Report.NetworkInterface> out = new ArrayList<>();
        for (JsonNode a : adapters) {
            if (out.size() >= 64) break;
            String name = WinData.str(a, "Name");
            if (name == null) continue;
            JsonNode s = stats.get(name);
            Counters c = s == null ? new Counters(null, null, null, null, null, null, null, null) : new Counters(
                    WinData.lng(s, "ReceivedBytes"), WinData.lng(s, "SentBytes"),
                    WinData.sum(WinData.lng(s, "ReceivedUnicastPackets"), WinData.lng(s, "ReceivedMulticastPackets"),
                            WinData.lng(s, "ReceivedBroadcastPackets")),
                    WinData.sum(WinData.lng(s, "SentUnicastPackets"), WinData.lng(s, "SentMulticastPackets"),
                            WinData.lng(s, "SentBroadcastPackets")),
                    WinData.lng(s, "ReceivedPacketErrors"), WinData.lng(s, "OutboundPacketErrors"),
                    WinData.lng(s, "ReceivedDiscardedPackets"), WinData.lng(s, "OutboundDiscardedPackets"));
            current.put(name, c);
            Counters p = previous == null ? null : previous.get(name);
            Double rxBps = null, txBps = null, rxPps = null, txPps = null, errPs = null, dropPs = null;
            if (p != null) {
                Double rx = Rates.perSecond(p.rxBytes(), c.rxBytes(), secs);
                Double tx = Rates.perSecond(p.txBytes(), c.txBytes(), secs);
                rxBps = rx == null ? null : Rates.round(rx * 8, 1);
                txBps = tx == null ? null : Rates.round(tx * 8, 1);
                rxPps = Rates.round(Rates.perSecond(p.rxPackets(), c.rxPackets(), secs), 2);
                txPps = Rates.round(Rates.perSecond(p.txPackets(), c.txPackets(), secs), 2);
                errPs = Rates.round(add(Rates.perSecond(p.rxErrors(), c.rxErrors(), secs), Rates.perSecond(p.txErrors(), c.txErrors(), secs)), 3);
                dropPs = Rates.round(add(Rates.perSecond(p.rxDropped(), c.rxDropped(), secs), Rates.perSecond(p.txDropped(), c.txDropped(), secs)), 3);
            }
            Long speedBps = WinData.lng(a, "Speed");
            Long mtu = WinData.lng(a, "MtuSize");
            String mac = WinData.str(a, "MacAddress");
            List<String> addr = ips.getOrDefault(name, List.of());
            out.add(new Report.NetworkInterface(name.length() > 128 ? name.substring(0, 128) : name,
                    operState(WinData.str(a, "Status")), mac == null || mac.isBlank() ? null : mac.replace('-', ':').toLowerCase(Locale.ROOT),
                    addr.size() > 32 ? addr.subList(0, 32) : addr,
                    speedBps == null || speedBps <= 0 ? null : speedBps / 1_000_000, mtu == null ? null : mtu.intValue(),
                    c.rxBytes(), c.txBytes(), c.rxPackets(), c.txPackets(), c.rxErrors(), c.txErrors(), c.rxDropped(),
                    c.txDropped(), rxBps, txBps, rxPps, txPps, errPs, dropPs));
        }
        previous = current;
        previousAt = now;
        b.network = out;
    }

    /** Get-NetAdapter Status -> the same words Linux uses (up / down). */
    static String operState(String status) {
        if (status == null) return null;
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "up" -> "up";
            case "disconnected", "disabled", "not present" -> "down";
            default -> status.toLowerCase(Locale.ROOT);
        };
    }

    private static Double add(Double a, Double b) {
        return a == null || b == null ? null : a + b;
    }
}
