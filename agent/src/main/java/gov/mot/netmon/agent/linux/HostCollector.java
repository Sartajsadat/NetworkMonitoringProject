package gov.mot.netmon.agent.linux;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.model.Report;

/** Hostname, distribution, kernel, uptime and CPU count. */
public class HostCollector implements Collector {

    private final Path proc;
    private final Path etc;

    public HostCollector(Path proc, Path etc) {
        this.proc = proc;
        this.etc = etc;
    }

    @Override
    public String name() {
        return "host";
    }

    @Override
    public void collect(ReportBuilder b, Instant now) throws Exception {
        double uptime = Double.parseDouble(ProcFs.read(proc.resolve("uptime")).trim().split("\\s+")[0]);
        Instant boot = now.minusMillis((long) (uptime * 1000));
        b.bootTime = boot;
        Map<String, String> os = osRelease(etc.resolve("os-release"));
        int cpus = (int) ProcFs.lines(proc.resolve("stat")).stream().filter(l -> l.matches("^cpu\\d+ .*")).count();
        b.host = new Report.Host(hostname(), "LINUX", os.getOrDefault("NAME", "Linux"), os.get("VERSION_ID"),
                ProcFs.stringOrNull(proc.resolve("sys/kernel/osrelease")), System.getProperty("os.arch"),
                (long) uptime, boot, cpus > 0 ? cpus : null);
    }

    private String hostname() {
        String h = ProcFs.stringOrNull(proc.resolve("sys/kernel/hostname"));
        if (h != null) return h;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    static Map<String, String> osRelease(Path file) {
        Map<String, String> m = new HashMap<>();
        try {
            List<String> lines = ProcFs.lines(file);
            for (String line : lines) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim().replaceAll("^\"|\"$", ""));
                }
            }
        } catch (Exception e) {
            // os-release is optional; fall back to defaults
        }
        return m;
    }
}
