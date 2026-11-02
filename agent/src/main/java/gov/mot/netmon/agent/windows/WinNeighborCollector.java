package gov.mot.netmon.agent.windows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.model.Report;

/** Neighbour table (Get-NetNeighbor): IPv4 and IPv6 addresses on local networks with their MAC. */
public class WinNeighborCollector implements Collector {

    @Override
    public String name() {
        return "neighbors";
    }

    @Override
    public void collect(ReportBuilder b, Instant now) throws Exception {
        List<Report.Neighbor> out = new ArrayList<>();
        for (JsonNode n : WinData.section(b, "neighbors")) {
            String ip = WinData.str(n, "IPAddress");
            String mac = WinData.str(n, "LinkLayerAddress");
            if (ip == null || mac == null || mac.length() != 17) continue;
            int pct = ip.indexOf('%');
            out.add(new Report.Neighbor(pct > 0 ? ip.substring(0, pct) : ip, mac.replace('-', ':').toLowerCase(Locale.ROOT),
                    WinData.str(n, "InterfaceAlias"), WinData.str(n, "State") == null ? null
                            : WinData.str(n, "State").toUpperCase(Locale.ROOT)));
            if (out.size() >= 512) break;
        }
        b.neighbors = out;
    }
}
