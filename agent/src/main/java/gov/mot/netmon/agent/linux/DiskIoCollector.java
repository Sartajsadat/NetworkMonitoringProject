package gov.mot.netmon.agent.linux;

import java.nio.file.Files;
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

/** Disk throughput and busy time from /proc/diskstats, whole devices only (not partitions). */
public class DiskIoCollector implements Collector {

    record Counters(long reads, long sectorsRead, long writes, long sectorsWritten, long ioMillis) {
    }

    private final Path proc;
    private final Path sys;
    private Map<String, Counters> previous;
    private Instant previousAt;

    public DiskIoCollector(Path proc, Path sys) {
        this.proc = proc;
        this.sys = sys;
    }

    @Override
    public String name() {
        return "diskio";
    }

    @Override
    public void collect(ReportBuilder b, Instant now) throws Exception {
        Map<String, Counters> current = new HashMap<>();
        List<String> names = new ArrayList<>();
        for (String line : ProcFs.lines(proc.resolve("diskstats"))) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 14) continue;
            String dev = f[2];
            if (dev.startsWith("loop") || dev.startsWith("ram") || dev.startsWith("zram")) continue;
            if (!Files.isDirectory(sys.resolve("block").resolve(dev))) continue; // partitions are not under /sys/block
            current.put(dev, new Counters(Long.parseLong(f[3]), Long.parseLong(f[5]), Long.parseLong(f[7]),
                    Long.parseLong(f[9]), Long.parseLong(f[12])));
            names.add(dev);
        }
        List<Report.DiskIo> out = new ArrayList<>();
        double secs = previousAt == null ? 0 : (now.toEpochMilli() - previousAt.toEpochMilli()) / 1000.0;
        for (String dev : names) {
            Counters c = current.get(dev);
            Counters p = previous == null ? null : previous.get(dev);
            if (out.size() >= 64) break;
            if (p == null) {
                out.add(new Report.DiskIo(dev, null, null, null, null, null));
                continue;
            }
            Double rBytes = Rates.perSecond(p.sectorsRead() * 512, c.sectorsRead() * 512, secs);
            Double wBytes = Rates.perSecond(p.sectorsWritten() * 512, c.sectorsWritten() * 512, secs);
            Double busy = Rates.perSecond(p.ioMillis(), c.ioMillis(), secs);
            out.add(new Report.DiskIo(dev, Rates.round(rBytes, 1), Rates.round(wBytes, 1),
                    Rates.round(Rates.perSecond(p.reads(), c.reads(), secs), 2),
                    Rates.round(Rates.perSecond(p.writes(), c.writes(), secs), 2),
                    busy == null ? null : Rates.round(Rates.clampPercent(busy / 10.0), 2)));
        }
        previous = current;
        previousAt = now;
        b.diskIo = out;
    }
}
