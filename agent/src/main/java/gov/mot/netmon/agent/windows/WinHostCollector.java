package gov.mot.netmon.agent.windows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.CollectorException;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.model.Report;

/** Hostname, Windows edition, version, build, architecture, uptime (Win32_OperatingSystem). */
public class WinHostCollector implements Collector {

    @Override
    public String name() {
        return "host";
    }

    @Override
    public void collect(ReportBuilder b, Instant now) throws Exception {
        List<JsonNode> os = WinData.section(b, "os");
        if (os.isEmpty()) throw new CollectorException("UNAVAILABLE", "Win32_OperatingSystem returned nothing");
        JsonNode r = os.get(0);
        Instant boot = WinData.time(r, "LastBootUpTime");
        b.bootTime = boot;
        Long totalKb = WinData.lng(r, "TotalVisibleMemorySize");
        b.memoryTotalBytes = totalKb == null ? null : totalKb * 1024;
        int cpus = 0;
        try {
            cpus = (int) WinData.section(b, "cpu").stream().filter(c -> !"_Total".equals(WinData.str(c, "Name"))).count();
        } catch (CollectorException e) {
            // CPU section failed: the cpu collector reports that; fall back to the JVM's view here
        }
        if (cpus == 0) cpus = Runtime.getRuntime().availableProcessors();
        String hostname = WinData.str(r, "CSName");
        b.host = new Report.Host(hostname == null ? "unknown" : hostname, "WINDOWS", WinData.str(r, "Caption"),
                WinData.str(r, "Version"), "build " + WinData.str(r, "BuildNumber"), WinData.str(r, "OSArchitecture"),
                boot == null ? null : Math.max(0, Duration.between(boot, now).toSeconds()), boot, cpus);
    }
}
