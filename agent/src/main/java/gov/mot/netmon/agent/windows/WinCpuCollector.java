package gov.mot.netmon.agent.windows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.Rates;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.model.Report;

/**
 * CPU usage from Win32_PerfRawData_PerfOS_Processor. PercentProcessorTime is an "inverse" 100 ns timer
 * (idle time), so usage = 100 * (1 - delta(PercentProcessorTime) / delta(Timestamp_Sys100NS)).
 * Windows has no load average or I/O wait: those stay empty (shown as "not available"), and the
 * processor queue length is reported as the run queue.
 */
public class WinCpuCollector implements Collector {

    record Sample(long idle, long timestamp) {
    }

    private Map<String, Sample> previous;

    @Override
    public String name() {
        return "cpu";
    }

    @Override
    public void collect(ReportBuilder b, Instant now) throws Exception {
        Map<String, Sample> current = new HashMap<>();
        for (JsonNode r : WinData.section(b, "cpu")) {
            Long idle = WinData.lng(r, "PercentProcessorTime");
            Long ts = WinData.lng(r, "Timestamp_Sys100NS");
            String name = WinData.str(r, "Name");
            if (idle != null && ts != null && name != null) current.put(name, new Sample(idle, ts));
        }
        Integer queue = null;
        try {
            List<JsonNode> sys = WinData.section(b, "system");
            if (!sys.isEmpty()) {
                Long q = WinData.lng(sys.get(0), "ProcessorQueueLength");
                queue = q == null ? null : q.intValue();
            }
        } catch (Exception e) {
            // queue length is optional
        }
        Double total = null;
        List<Double> perCore = null;
        if (previous != null) {
            total = usage(previous.get("_Total"), current.get("_Total"));
            List<String> cores = current.keySet().stream().filter(k -> !k.equals("_Total") && k.matches("\\d+(,\\d+)?"))
                    .sorted(Comparator.comparingInt(WinCpuCollector::coreOrder)).toList();
            perCore = new ArrayList<>();
            for (String k : cores) {
                Double u = usage(previous.get(k), current.get(k));
                perCore.add(u == null ? 0.0 : u);
            }
        }
        previous = current;
        b.cpu = new Report.Cpu(total, null, perCore, null, null, null, queue);
    }

    /** Instance names are "0", "1" … or "0,0", "0,1" on multi-group systems. */
    private static int coreOrder(String name) {
        String[] p = name.split(",");
        return p.length == 2 ? Integer.parseInt(p[0]) * 1000 + Integer.parseInt(p[1]) : Integer.parseInt(p[0]);
    }

    static Double usage(Sample p, Sample c) {
        if (p == null || c == null || c.timestamp() <= p.timestamp() || c.idle() < p.idle()) return null;
        double idleFraction = (double) (c.idle() - p.idle()) / (c.timestamp() - p.timestamp());
        return Rates.round(Rates.clampPercent(100.0 * (1.0 - idleFraction)), 2);
    }
}
