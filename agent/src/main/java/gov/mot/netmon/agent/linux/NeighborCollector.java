package gov.mot.netmon.agent.linux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.model.Report;

/**
 * The IPv4 neighbour (ARP) table from /proc/net/arp: which addresses on directly connected
 * networks answered recently, and their MAC. Read-only; nothing is sent on the network.
 */
public class NeighborCollector implements Collector {

    static final int MAX = 512;
    private static final Pattern MAC = Pattern.compile("^([0-9a-f]{2}:){5}[0-9a-f]{2}$");
    private final Path proc;

    public NeighborCollector(Path proc) {
        this.proc = proc;
    }

    @Override
    public String name() {
        return "neighbors";
    }

    @Override
    public void collect(ReportBuilder b, Instant now) throws Exception {
        List<String> lines = Files.readAllLines(proc.resolve("net/arp"));
        List<Report.Neighbor> out = new ArrayList<>();
        for (int i = 1; i < lines.size() && out.size() < MAX; i++) {
            String[] c = lines.get(i).trim().split("\\s+");
            if (c.length < 6) continue;
            int flags;
            try {
                flags = Integer.decode(c[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            String mac = c[3].toLowerCase();
            if ((flags & 0x2) == 0 || !MAC.matcher(mac).matches() || mac.equals("00:00:00:00:00:00")) continue; // incomplete
            out.add(new Report.Neighbor(c[0], mac, c[5], (flags & 0x4) != 0 ? "PERMANENT" : "REACHABLE"));
        }
        b.neighbors = out;
    }
}
