package gov.mot.netmon.agent.sites;

import java.nio.file.Path;
import java.util.Properties;

/**
 * Website blocking (agent.properties). OFF unless the computer's own administrator sets
 * {@code sites.enabled=true}; the platform can never switch it on.
 *
 * <ul>
 * <li>Windows: the agent edits the hosts file itself, so the service must run as LocalSystem.
 * <li>Linux: the agent runs {@code sudo -n <sites.helper> <domain>...}; the root-owned helper and its
 * sudoers entry are installed by deploy/linux/enable-sites.sh.
 * </ul>
 */
public record SiteSettings(boolean enabled, Path hostsFile, Path sudo, Path helper, Path ipconfig) {

    public static SiteSettings fromProperties(Properties p, boolean windows) {
        String root = System.getenv("SystemRoot");
        Path sys32 = Path.of(root == null || root.isBlank() ? "C:\\Windows" : root, "System32");
        Path defaultHosts = windows ? sys32.resolve("drivers").resolve("etc").resolve("hosts") : Path.of("/etc/hosts");
        return new SiteSettings(
                Boolean.parseBoolean(p.getProperty("sites.enabled", "false").trim()),
                Path.of(p.getProperty("sites.hosts-file", defaultHosts.toString()).trim()),
                Path.of(p.getProperty("firewall.sudo-path", "/usr/bin/sudo").trim()),
                Path.of(p.getProperty("sites.helper", "/usr/lib/netmon-agent/netmon-hosts").trim()),
                sys32.resolve("ipconfig.exe"));
    }
}
