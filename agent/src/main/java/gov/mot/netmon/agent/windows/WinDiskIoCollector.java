package gov.mot.netmon.agent.windows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.Rates;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.model.Report;

/**
 * Physical disk activity from Win32_PerfRawData_PerfDisk_PhysicalDisk raw counters. Bytes and
 * operations are cumulative; busy % = 100 - delta(PercentIdleTime) / delta(Timestamp_Sys100NS).
 */
public class WinDiskIoCollector implements Collector {

    record Counters(Long readBytes, Long writeBytes, Long reads, Long writes, Long idle, Long timestamp) {
    }

    private Map<String, Counters> previous;
    private Instant previousAt;

    @Override
    public String name() {
        return "diskio";
    }

    @Override
    public void collect(ReportBuilder b, Instant now) throws Exception {
        Map<String, Counters> current = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (JsonNode r : WinData.section(b, "diskio")) {
            String name = WinData.str(r, "Name");
            if (name == null) continue;
            current.put(name, new Counters(WinData.lng(r, "DiskReadBytesPersec"), WinData.lng(r, "DiskWriteBytesPersec"),
                    WinData.lng(r, "DiskReadsPersec"), WinData.lng(r, "DiskWritesPersec"), WinData.lng(r, "PercentIdleTime"),
                    WinData.lng(r, "Timestamp_Sys100NS")));
            order.add(name);
        }
        double secs = previousAt == null ? 0 : (now.toEpochMilli() - previousAt.toEpochMilli()) / 1000.0;
        List<Report.DiskIo> out = new ArrayList<>();
        for (String name : order) {
            if (out.size() >= 64) break;
            String device = name.replaceAll("[^\\p{Alnum}@._:/+-]", "_"); // "0 C:" -> "0_C:"
            Counters c = current.get(name);
            Counters p = previous == null ? null : previous.get(name);
            if (p == null) {
                out.add(new Report.DiskIo(device, null, null, null, null, null));
                continue;
            }
            Double busy = null;
            if (c.idle() != null && p.idle() != null && c.timestamp() != null && p.timestamp() != null
                    && c.timestamp() > p.timestamp() && c.idle() >= p.idle()) {
                double idlePct = 100.0 * (c.idle() - p.idle()) / (c.timestamp() - p.timestamp());
                busy = Rates.round(Rates.clampPercent(100.0 - idlePct), 2);
            }
            out.add(new Report.DiskIo(device,
                    Rates.round(Rates.perSecond(p.readBytes(), c.readBytes(), secs), 1),
                    Rates.round(Rates.perSecond(p.writeBytes(), c.writeBytes(), secs), 1),
                    Rates.round(Rates.perSecond(p.reads(), c.reads(), secs), 2),
                    Rates.round(Rates.perSecond(p.writes(), c.writes(), secs), 2), busy));
        }
        previous = current;
        previousAt = now;
        b.diskIo = out;
    }
}
