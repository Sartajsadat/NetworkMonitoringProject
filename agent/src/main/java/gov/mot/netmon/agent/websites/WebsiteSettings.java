package gov.mot.netmon.agent.websites;

import java.nio.file.Path;
import java.util.Properties;

/**
 * Local website-blocking settings (agent.properties). OFF unless this device's administrator turns it on:
 * <ul>
 * <li>{@code websites.enabled=true}: the agent keeps NetMon's section of the hosts file equal to the
 * platform's blocked list;
 * <li>{@code websites.mode}: {@code helper} (Linux default: the agent stays unprivileged and writes the
 * list to {@code websites.request-file}; the root helper netmon-hosts-apply updates the hosts file) or
 * {@code direct} (Windows default: the agent writes the hosts file itself).
 * </ul>
 */
public record WebsiteSettings(boolean enabled, boolean direct, Path hostsFile, Path requestFile) {

    public static WebsiteSettings fromProperties(Properties p, Path stateDir, boolean windows) {
        String root = System.getenv("SystemRoot");
        String defaultHosts = windows
                ? Path.of(root == null || root.isBlank() ? "C:\\Windows" : root, "System32", "drivers", "etc", "hosts").toString()
                : "/etc/hosts";
        String mode = p.getProperty("websites.mode", windows ? "direct" : "helper").trim();
        return new WebsiteSettings(
                Boolean.parseBoolean(p.getProperty("websites.enabled", "false").trim()),
                "direct".equalsIgnoreCase(mode),
                Path.of(p.getProperty("websites.hosts-file", defaultHosts).trim()),
                Path.of(p.getProperty("websites.request-file", stateDir.resolve("blocked-sites.txt").toString()).trim()));
    }
}
