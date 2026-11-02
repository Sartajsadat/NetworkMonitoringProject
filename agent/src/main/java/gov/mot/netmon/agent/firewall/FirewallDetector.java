package gov.mot.netmon.agent.firewall;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import gov.mot.netmon.agent.collect.CommandRunner;

/**
 * Finds the firewall manager in use, so NetMon never mixes managers:
 * <ul>
 * <li>UFW enabled (/etc/ufw/ufw.conf) - UFW commands;
 * <li>firewalld running - not supported yet (it owns the firewall; mixing is unsafe);
 * <li>otherwise nftables, in NetMon's own table;
 * <li>Windows - Windows Defender Firewall.
 * </ul>
 */
public final class FirewallDetector {

    private FirewallDetector() {
    }

    public static FirewallBackend linux(FirewallSettings s, Path etcRoot, Path workDir) {
        String choice = s.backend();
        boolean ufwEnabled = ufwEnabled(etcRoot);
        if ("none".equals(choice)) return new FirewallBackend.None("NONE", "firewall.backend=none");
        if ("ufw".equals(choice) || ("auto".equals(choice) && ufwEnabled)) return ufw(s);
        if ("auto".equals(choice) && firewalldActive(s.systemctl())) {
            return new FirewallBackend.None("UNSUPPORTED", "firewalld is running on this server; NetMon does not change firewalld-managed"
                    + " firewalls yet. Rules stay in dry run.");
        }
        if ("auto".equals(choice) || "nftables".equals(choice)) {
            if (!Files.isExecutable(s.nft())) return new FirewallBackend.None("NONE", "nft not found at " + s.nft());
            CommandRunner nft = CommandRunner.fixed(s.nft(), 30);
            String problem = null;
            try {
                CommandRunner.Result r = nft.run(List.of("list", "tables"));
                if (r.exitCode() != 0) problem = "The agent may not use nftables (" + FirewallBackend.firstLine(r.stdout())
                        + "). Run deploy/linux/enable-firewall.sh.";
            } catch (Exception e) {
                problem = "nft could not run: " + e.getMessage();
            }
            return new NftablesBackend(nft, workDir, problem);
        }
        return new FirewallBackend.None("NONE", "Unknown firewall.backend '" + choice + "'");
    }

    static boolean ufwEnabled(Path etcRoot) {
        try {
            for (String l : Files.readAllLines(etcRoot.resolve("ufw/ufw.conf"))) {
                if (l.trim().replace(" ", "").equalsIgnoreCase("ENABLED=yes")) return true;
            }
        } catch (Exception e) {
            // no UFW
        }
        return false;
    }

    static boolean firewalldActive(Path systemctl) {
        if (!Files.isExecutable(systemctl)) return false;
        try {
            return CommandRunner.fixed(systemctl, 10).run(List.of("is-active", "firewalld")).stdout().trim().equals("active");
        } catch (Exception e) {
            return false;
        }
    }

    static FirewallBackend ufw(FirewallSettings s) {
        if (!Files.isExecutable(s.sudo())) return new FirewallBackend.None("NONE", "sudo not found at " + s.sudo());
        UfwBackend b = new UfwBackend(CommandRunner.fixed(s.sudo(), 30), s.ufw().toString(), null);
        String problem = null;
        try {
            b.status();
        } catch (FirewallBackend.FirewallException e) {
            problem = e.getMessage().contains("password") || e.getMessage().contains("sudo")
                    ? "The agent may not run ufw through sudo. Run deploy/linux/enable-firewall.sh." : e.getMessage();
        }
        return problem == null ? b : new UfwBackend(CommandRunner.fixed(s.sudo(), 30), s.ufw().toString(), problem);
    }

    public static FirewallBackend windows(Path powershell) {
        if (!Files.isExecutable(powershell)) return new FirewallBackend.None("NONE", "PowerShell not found at " + powershell);
        WindowsFirewallBackend b = new WindowsFirewallBackend(WindowsFirewallBackend.powershell(powershell), null);
        try {
            b.tags();
            return b;
        } catch (FirewallBackend.FirewallException e) {
            return new WindowsFirewallBackend(WindowsFirewallBackend.powershell(powershell),
                    "Windows Firewall cannot be managed: " + e.getMessage() + " (the agent service must run as LocalSystem)");
        }
    }
}
