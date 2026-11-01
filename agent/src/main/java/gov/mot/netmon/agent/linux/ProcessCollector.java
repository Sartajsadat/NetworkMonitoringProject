package gov.mot.netmon.agent.linux;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.Rates;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.model.Report;

/**
 * Top processes by CPU and by memory. Deliberately collects only name, pid, owner, CPU, memory,
 * state and start time: never command lines or environment (they can contain passwords and tokens).
 */
public class ProcessCollector implements Collector {

    static final int TOP_N = 15;

    record Parsed(long pid, String name, String state, long ticks, long startTicks) {
    }

    private final Path proc;
    private final Path etc;
    private final int ticksPerSecond;
    private Map<Long, Long> previousTicks = Map.of();
    private Instant previousAt;
    private Map<Integer, String> users;

    public ProcessCollector(Path proc, Path etc, int ticksPerSecond) {
        this.proc = proc;
        this.etc = etc;
        this.ticksPerSecond = ticksPerSecond;
    }

    @Override
    public String name() {
        return "processes";
    }

    @Override
    public void collect(ReportBuilder b, Instant now) throws Exception {
        if (users == null) users = readUsers(etc.resolve("passwd"));
        double secs = previousAt == null ? 0 : (now.toEpochMilli() - previousAt.toEpochMilli()) / 1000.0;
        Map<Long, Long> ticksNow = new HashMap<>();
        List<Report.ProcessInfo> all = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(proc, p -> p.getFileName().toString().matches("\\d+"))) {
            for (Path dir : ds) {
                try {
                    Parsed p = parseStat(ProcFs.read(dir.resolve("stat")));
                    List<String> status = ProcFs.lines(dir.resolve("status"));
                    Long rss = ProcFs.kbValue(status, "VmRSS");
                    String user = uidName(status);
                    ticksNow.put(p.pid(), p.ticks());
                    Long prev = previousTicks.get(p.pid());
                    Double cpu = prev == null || secs <= 0 || p.ticks() < prev ? null
                            : Rates.round(100.0 * (p.ticks() - prev) / (secs * ticksPerSecond), 2);
                    Double memPct = rss != null && b.memoryTotalBytes != null && b.memoryTotalBytes > 0
                            ? Rates.round(100.0 * rss / b.memoryTotalBytes, 2) : null;
                    Instant started = b.bootTime == null ? null
                            : b.bootTime.plusMillis(p.startTicks() * 1000 / ticksPerSecond);
                    all.add(new Report.ProcessInfo(p.pid(), p.name(), user, cpu, rss, memPct, p.state(), started));
                } catch (IOException | RuntimeException e) {
                    // process exited while reading, or kernel thread without status details: skip
                }
            }
        }
        previousTicks = ticksNow;
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

    /** Parses /proc/[pid]/stat. The name is between the first '(' and the LAST ')' and may contain spaces. */
    static Parsed parseStat(String stat) {
        int open = stat.indexOf('(');
        int close = stat.lastIndexOf(')');
        long pid = Long.parseLong(stat.substring(0, open).trim());
        String name = stat.substring(open + 1, close);
        String[] f = stat.substring(close + 2).trim().split("\\s+");
        // f[0] = state (field 3); utime = field 14 -> f[11]; stime = field 15 -> f[12]; starttime = field 22 -> f[19]
        long ticks = Long.parseLong(f[11]) + Long.parseLong(f[12]);
        return new Parsed(pid, name.length() > 64 ? name.substring(0, 64) : name, f[0], ticks, Long.parseLong(f[19]));
    }

    private String uidName(List<String> status) {
        for (String line : status) {
            if (line.startsWith("Uid:")) {
                int uid = Integer.parseInt(line.substring(4).trim().split("\\s+")[0]);
                return users.getOrDefault(uid, String.valueOf(uid));
            }
        }
        return null;
    }

    static Map<Integer, String> readUsers(Path passwd) {
        Map<Integer, String> m = new HashMap<>();
        try {
            for (String line : ProcFs.lines(passwd)) {
                String[] f = line.split(":");
                if (f.length > 2 && f[2].matches("\\d+")) m.put(Integer.parseInt(f[2]), f[0]);
            }
        } catch (IOException e) {
            // numeric uids are reported instead
        }
        return m;
    }
}
