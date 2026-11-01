package gov.mot.netmon.agent.capture;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Local packet capture settings (agent.properties). Capture is OFF unless the server's own
 * administrator sets {@code capture.enabled=true}: the platform cannot switch it on remotely.
 *
 * @param interfaces allowlist of interface names (or Windows descriptions); empty = every real
 *                   interface dumpcap lists (pseudo interfaces such as "any" must be listed explicitly)
 */
public record CaptureSettings(
        boolean enabled,
        Path dumpcap,
        List<String> interfaces,
        int maxDurationSeconds,
        long maxBytes,
        Path spoolDir,
        long minFreeDiskBytes,
        int rotateSeconds,
        int pollSeconds) {

    public static final long MB = 1024L * 1024;
    /** dumpcap starts a new piece at this size even before {@link #rotateSeconds} (KB for dumpcap). */
    public static final int ROTATE_KILOBYTES = 32 * 1024;

    public static CaptureSettings fromProperties(Properties p, Path stateDir, boolean windows) {
        String list = p.getProperty("capture.interfaces", "").trim();
        return new CaptureSettings(
                Boolean.parseBoolean(p.getProperty("capture.enabled", "false").trim()),
                Path.of(p.getProperty("capture.dumpcap-path", defaultDumpcap(windows)).trim()),
                list.isEmpty() ? List.of() : Arrays.stream(list.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                clamp(Integer.parseInt(p.getProperty("capture.max-duration-seconds", "3600").trim()), 10, 86_400),
                Math.max(MB, Long.parseLong(p.getProperty("capture.max-megabytes", "1024").trim()) * MB),
                Path.of(p.getProperty("capture.spool-dir", stateDir.resolve("captures").toString()).trim()),
                Math.max(0, Long.parseLong(p.getProperty("capture.min-free-disk-megabytes", "512").trim()) * MB),
                clamp(Integer.parseInt(p.getProperty("capture.rotate-seconds", "15").trim()), 5, 300),
                clamp(Integer.parseInt(p.getProperty("capture.poll-seconds", "5").trim()), 2, 60));
    }

    static String defaultDumpcap(boolean windows) {
        if (!windows) return "/usr/bin/dumpcap";
        String pf = System.getenv("ProgramFiles");
        return Path.of(pf == null || pf.isBlank() ? "C:\\Program Files" : pf, "Wireshark", "dumpcap.exe").toString();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static CaptureSettings disabled(Path stateDir) {
        return fromProperties(new Properties(), stateDir, false);
    }
}
