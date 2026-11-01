package gov.mot.netmon.agent.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/**
 * Local agent configuration, read from a properties file (default /etc/netmon-agent/agent.properties).
 * Only local, operator-controlled settings live here; monitored services and the report interval
 * come from the platform.
 */
public record AgentSettings(
        URI platformUrl,
        Path caCertificate,
        Path stateDir,
        Path procRoot,
        Path sysRoot,
        Path etcRoot,
        Path systemctl,
        int maxBufferedReports,
        Duration credentialRotation,
        boolean allowInsecureHttp,
        int clockTicksPerSecond) {

    public static final Path DEFAULT_FILE = Path.of("/etc/netmon-agent/agent.properties");

    public static AgentSettings load(Path file) throws IOException {
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
        }
        return fromProperties(p);
    }

    public static AgentSettings fromProperties(Properties p) {
        String url = p.getProperty("platform.url", "").trim();
        String ca = p.getProperty("tls.ca-certificate", "").trim();
        return new AgentSettings(
                url.isEmpty() ? null : URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) : url),
                ca.isEmpty() ? null : Path.of(ca),
                Path.of(p.getProperty("state.dir", "/var/lib/netmon-agent")),
                Path.of(p.getProperty("proc.root", "/proc")),
                Path.of(p.getProperty("sys.root", "/sys")),
                Path.of(p.getProperty("etc.root", "/etc")),
                Path.of(p.getProperty("systemctl.path", "/usr/bin/systemctl")),
                Integer.parseInt(p.getProperty("buffer.max-reports", "240")),
                Duration.ofDays(Long.parseLong(p.getProperty("credentials.rotate-after-days", "30"))),
                Boolean.parseBoolean(p.getProperty("tls.allow-insecure-http", "false")),
                Integer.parseInt(p.getProperty("clock.ticks-per-second", "100")));
    }

    public AgentSettings withPlatformUrl(URI url) {
        return new AgentSettings(url, caCertificate, stateDir, procRoot, sysRoot, etcRoot, systemctl, maxBufferedReports,
                credentialRotation, allowInsecureHttp, clockTicksPerSecond);
    }

    /** Plain HTTP is refused unless explicitly allowed or talking to this machine (development). */
    public void requireSecureTransport() {
        if (platformUrl == null) {
            throw new IllegalStateException("platform.url is not configured");
        }
        String scheme = platformUrl.getScheme();
        String host = platformUrl.getHost();
        boolean local = "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
        if (!"https".equalsIgnoreCase(scheme) && !(("http".equalsIgnoreCase(scheme)) && (allowInsecureHttp || local))) {
            throw new IllegalStateException("platform.url must use https:// (set tls.allow-insecure-http=true only in a lab)");
        }
    }
}
