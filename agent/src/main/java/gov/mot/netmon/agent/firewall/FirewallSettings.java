package gov.mot.netmon.agent.firewall;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Local firewall settings (agent.properties). Both switches are OFF by default and can only be
 * changed by the server's own administrator:
 * <ul>
 * <li>{@code firewall.enabled=true}: the agent reports the firewall state and checks rules (dry run);
 * <li>{@code firewall.enforcement=true}: the agent may also really change the firewall.
 * </ul>
 * The platform can never widen these settings.
 */
public record FirewallSettings(
        boolean enabled,
        boolean enforcement,
        String backend,
        Path nft,
        Path ufw,
        Path sudo,
        Path systemctl,
        List<String> protectedAddresses,
        List<Integer> managementPorts,
        int pollSeconds,
        Path stateFile) {

    public static FirewallSettings fromProperties(Properties p, Path stateDir, boolean windows) {
        String prot = p.getProperty("firewall.protected-addresses", "").trim();
        String ports = p.getProperty("firewall.management-ports", "22,3389,5985,5986").trim();
        return new FirewallSettings(
                Boolean.parseBoolean(p.getProperty("firewall.enabled", "false").trim()),
                Boolean.parseBoolean(p.getProperty("firewall.enforcement", "false").trim()),
                p.getProperty("firewall.backend", "auto").trim().toLowerCase(Locale.ROOT),
                Path.of(p.getProperty("firewall.nft-path", "/usr/sbin/nft").trim()),
                Path.of(p.getProperty("firewall.ufw-path", "/usr/sbin/ufw").trim()),
                Path.of(p.getProperty("firewall.sudo-path", "/usr/bin/sudo").trim()),
                Path.of(p.getProperty("systemctl.path", "/usr/bin/systemctl").trim()),
                prot.isEmpty() ? List.of() : Arrays.stream(prot.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                Arrays.stream(ports.split(",")).map(String::trim).filter(s -> s.matches("\\d{1,5}")).map(Integer::valueOf).toList(),
                Math.max(3, Math.min(120, Integer.parseInt(p.getProperty("firewall.poll-seconds", "10").trim()))),
                stateDir.resolve("firewall-rules.json"));
    }

    public static FirewallSettings disabled(Path stateDir) {
        return fromProperties(new Properties(), stateDir, false);
    }
}
