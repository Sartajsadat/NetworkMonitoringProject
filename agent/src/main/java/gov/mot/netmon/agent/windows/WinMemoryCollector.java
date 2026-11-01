package gov.mot.netmon.agent.windows;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.CollectorException;
import gov.mot.netmon.agent.collect.Rates;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.model.Report;

/**
 * Physical memory from Win32_OperatingSystem (FreePhysicalMemory is the memory available to
 * programs) and the page file from Win32_PageFileUsage, reported in the "swap" fields.
 */
public class WinMemoryCollector implements Collector {

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public void collect(ReportBuilder b, Instant now) throws Exception {
        List<JsonNode> os = WinData.section(b, "os");
        if (os.isEmpty()) throw new CollectorException("UNAVAILABLE", "Win32_OperatingSystem returned nothing");
        Long totalKb = WinData.lng(os.get(0), "TotalVisibleMemorySize");
        Long freeKb = WinData.lng(os.get(0), "FreePhysicalMemory");
        Long total = totalKb == null ? null : totalKb * 1024;
        Long available = freeKb == null ? null : freeKb * 1024;
        Long used = total != null && available != null ? total - available : null;
        Double pct = used != null && total > 0 ? Rates.round(100.0 * used / total, 2) : null;

        Long pageTotal = null, pageUsed = null;
        try {
            for (JsonNode pf : WinData.section(b, "pagefile")) {
                Long alloc = WinData.lng(pf, "AllocatedBaseSize"); // MB
                Long cur = WinData.lng(pf, "CurrentUsage");        // MB
                if (alloc != null) pageTotal = (pageTotal == null ? 0 : pageTotal) + alloc * 1024 * 1024;
                if (cur != null) pageUsed = (pageUsed == null ? 0 : pageUsed) + cur * 1024 * 1024;
            }
        } catch (CollectorException e) {
            // page file details unavailable: leave empty rather than guessing
        }
        Double pagePct = pageTotal != null && pageUsed != null && pageTotal > 0 ? Rates.round(100.0 * pageUsed / pageTotal, 2) : null;
        if (pageTotal == null) pageTotal = 0L; // no page file configured
        b.memoryTotalBytes = total;
        b.memory = new Report.Memory(total, available, used, pct, pageTotal, pageUsed, pagePct);
    }
}
