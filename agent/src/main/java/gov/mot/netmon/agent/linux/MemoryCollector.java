package gov.mot.netmon.agent.linux;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.Rates;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.model.Report;

/** Memory from /proc/meminfo. "Used" = MemTotal - MemAvailable (what applications can not get back). */
public class MemoryCollector implements Collector {

    private final Path proc;

    public MemoryCollector(Path proc) {
        this.proc = proc;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public void collect(ReportBuilder b, Instant now) throws Exception {
        List<String> lines = ProcFs.lines(proc.resolve("meminfo"));
        Long total = ProcFs.kbValue(lines, "MemTotal");
        Long available = ProcFs.kbValue(lines, "MemAvailable");
        Long swapTotal = ProcFs.kbValue(lines, "SwapTotal");
        Long swapFree = ProcFs.kbValue(lines, "SwapFree");
        Long used = total != null && available != null ? total - available : null;
        Double usedPct = used != null && total > 0 ? Rates.round(100.0 * used / total, 2) : null;
        Long swapUsed = swapTotal != null && swapFree != null ? swapTotal - swapFree : null;
        Double swapPct = swapUsed != null && swapTotal > 0 ? Rates.round(100.0 * swapUsed / swapTotal, 2) : null;
        b.memoryTotalBytes = total;
        b.memory = new Report.Memory(total, available, used, usedPct, swapTotal, swapUsed, swapPct);
    }
}
