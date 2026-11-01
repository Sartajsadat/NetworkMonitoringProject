package gov.mot.netmon.agent.linux;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.Rates;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.model.Report;

/**
 * CPU usage from /proc/stat jiffy deltas, load average from /proc/loadavg.
 * usage = 100 * (1 - (idle + iowait) / total) over the interval since the previous call.
 */
public class CpuCollector implements Collector {

    record Times(long total, long idle, long iowait) {
    }

    private final Path proc;
    private Map<String, Times> previous;

    public CpuCollector(Path proc) {
        this.proc = proc;
    }

    @Override
    public String name() {
        return "cpu";
    }

    @Override
    public void collect(ReportBuilder b, Instant now) throws Exception {
        Map<String, Times> current = new HashMap<>();
        List<String> order = new ArrayList<>();
        Integer runQueue = null;
        for (String line : ProcFs.lines(proc.resolve("stat"))) {
            if (line.startsWith("cpu")) {
                String[] f = line.trim().split("\\s+");
                long user = l(f, 1), nice = l(f, 2), system = l(f, 3), idle = l(f, 4), iowait = l(f, 5), irq = l(f, 6),
                        softirq = l(f, 7), steal = l(f, 8);
                // guest time is already included in user/nice
                long total = user + nice + system + idle + iowait + irq + softirq + steal;
                current.put(f[0], new Times(total, idle, iowait));
                order.add(f[0]);
            } else if (line.startsWith("procs_running ")) {
                runQueue = Integer.parseInt(line.split("\\s+")[1]);
            }
        }
        String[] load = ProcFs.read(proc.resolve("loadavg")).trim().split("\\s+");
        Double usage = null, iowaitPct = null;
        List<Double> perCore = null;
        if (previous != null) {
            Times p = previous.get("cpu"), c = current.get("cpu");
            usage = usage(p, c);
            iowaitPct = (p != null && c != null && c.total() > p.total())
                    ? Rates.round(Rates.clampPercent(100.0 * (c.iowait() - p.iowait()) / (c.total() - p.total())), 2) : null;
            perCore = new ArrayList<>();
            for (String k : order) {
                if (!k.equals("cpu")) {
                    Double u = usage(previous.get(k), current.get(k));
                    perCore.add(u == null ? 0.0 : u);
                }
            }
        }
        previous = current;
        b.cpu = new Report.Cpu(usage, iowaitPct, perCore, Double.parseDouble(load[0]), Double.parseDouble(load[1]),
                Double.parseDouble(load[2]), runQueue);
    }

    private static Double usage(Times p, Times c) {
        if (p == null || c == null || c.total() <= p.total()) {
            return null;
        }
        double idle = (c.idle() + c.iowait()) - (p.idle() + p.iowait());
        return Rates.round(Rates.clampPercent(100.0 * (1.0 - idle / (c.total() - p.total()))), 2);
    }

    private static long l(String[] f, int i) {
        return i < f.length ? Long.parseLong(f[i]) : 0L;
    }
}
