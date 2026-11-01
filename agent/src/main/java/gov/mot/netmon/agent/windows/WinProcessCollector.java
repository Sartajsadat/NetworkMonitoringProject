package gov.mot.netmon.agent.windows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.Rates;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.model.Report;

/**
 * Top processes by CPU and memory (Get-Process). The process owner is not collected because Windows
 * only reveals it to administrators, and the agent runs without administrator rights.
 */
public class WinProcessCollector implements Collector {

    static final int TOP_N = 15;

    private Map<Long, Double> previousCpu = Map.of();
    private Instant previousAt;

    @Override
    public String name() {
        return "processes";
    }

    @Override
    public void collect(ReportBuilder b, Instant now) throws Exception {
        double secs = previousAt == null ? 0 : (now.toEpochMilli() - previousAt.toEpochMilli()) / 1000.0;
        Map<Long, Double> cpuNow = new HashMap<>();
        List<Report.ProcessInfo> all = new ArrayList<>();
        for (JsonNode r : WinData.section(b, "processes")) {
            Long pid = WinData.lng(r, "Id");
            String name = WinData.str(r, "ProcessName");
            if (pid == null || name == null || pid == 0) continue; // pid 0 = "Idle", not a real process
            Double cpuSeconds = WinData.dbl(r, "CpuSeconds");
            Long mem = WinData.lng(r, "WorkingSet64");
            Double cpu = null;
            if (cpuSeconds != null) {
                cpuNow.put(pid, cpuSeconds);
                Double prev = previousCpu.get(pid);
                if (prev != null && secs > 0 && cpuSeconds >= prev) cpu = Rates.round(100.0 * (cpuSeconds - prev) / secs, 2);
            }
            Double memPct = mem != null && b.memoryTotalBytes != null && b.memoryTotalBytes > 0
                    ? Rates.round(100.0 * mem / b.memoryTotalBytes, 2) : null;
            all.add(new Report.ProcessInfo(pid, name.length() > 64 ? name.substring(0, 64) : name, null, cpu, mem, memPct,
                    null, WinData.time(r, "StartTime")));
        }
        previousCpu = cpuNow;
        previousAt = now;
        Map<Long, Report.ProcessInfo> top = new LinkedHashMap<>();
        all.stream().filter(p -> p.cpuPercent() != null && p.cpuPercent() > 0)
                .sorted(Comparator.comparing(Report.ProcessInfo::cpuPercent).reversed()).limit(TOP_N)
                .forEach(p -> top.put(p.pid(), p));
        all.stream().filter(p -> p.memoryBytes() != null)
                .sorted(Comparator.comparing(Report.ProcessInfo::memoryBytes).reversed()).limit(TOP_N)
                .forEach(p -> top.putIfAbsent(p.pid(), p));
        b.processes = new ArrayList<>(top.values()).subList(0, Math.min(top.size(), 30));
    }
}
