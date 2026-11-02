package gov.mot.netmon.agent.firewall;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gov.mot.netmon.agent.collect.CommandRunner;

/**
 * UFW, used when UFW is the active firewall manager (rules are not mixed with a separate nftables
 * table). Runs {@code sudo -n /usr/sbin/ufw ...}; the sudoers entry installed by
 * deploy/linux/enable-firewall.sh allows exactly that program. NetMon rules carry the comment
 * {@code netmon:<tag>} and are prepended, so they are evaluated before the server's own rules.
 * Recovery: {@code ufw status numbered}, then {@code ufw delete N} for each rule commented netmon.
 */
public class UfwBackend implements FirewallBackend {

    static final Pattern NUMBERED = Pattern.compile("^\\[\\s*(\\d+)\\]\\s.*#\\s*netmon:([0-9a-f]{8,16})\\s*$");
    static final Pattern ANY_TAG = Pattern.compile("#\\s*netmon:([0-9a-f]{8,16})\\s*$");

    private final CommandRunner sudo;
    private final String ufwPath;
    private final String problem;

    /** @param sudo runs the fixed sudo binary; the first arguments are always "-n", ufwPath */
    public UfwBackend(CommandRunner sudo, String ufwPath, String problem) {
        this.sudo = sudo;
        this.ufwPath = ufwPath;
        this.problem = problem;
    }

    @Override
    public String name() {
        return "UFW";
    }

    @Override
    public String problem() {
        return problem;
    }

    /** ufw arguments after "ufw" (without --dry-run). */
    public static List<String> ruleArgs(FwRule r) {
        List<String> a = new ArrayList<>(List.of("prepend", r.block() ? "deny" : "allow"));
        if (!r.inbound()) a.add("out");
        if (!"ANY".equals(r.protocol())) {
            a.add("proto");
            a.add(r.protocol().toLowerCase(Locale.ROOT));
        }
        String local = r.local() == null ? "any" : r.local();
        String fromAddr = r.inbound() ? r.remote() : local;
        String fromPort = r.inbound() ? r.remotePort() : r.localPort();
        String toAddr = r.inbound() ? local : r.remote();
        String toPort = r.inbound() ? r.localPort() : r.remotePort();
        a.add("from");
        a.add(fromAddr);
        if (fromPort != null) {
            a.add("port");
            a.add(fromPort.replace('-', ':'));
        }
        a.add("to");
        a.add(toAddr);
        if (toPort != null) {
            a.add("port");
            a.add(toPort.replace('-', ':'));
        }
        a.add("comment");
        a.add("netmon:" + r.tag());
        return a;
    }

    @Override
    public String describe(FwRule r) {
        return "ufw " + String.join(" ", ruleArgs(r));
    }

    private CommandRunner.Result ufw(List<String> args) throws FirewallException {
        List<String> all = new ArrayList<>(List.of("-n", ufwPath));
        all.addAll(args);
        try {
            return sudo.run(all);
        } catch (IOException e) {
            throw new FirewallException("ufw could not run: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FirewallException("interrupted");
        }
    }

    @Override
    public void check(FwRule r) throws FirewallException {
        List<String> a = new ArrayList<>(List.of("--dry-run"));
        a.addAll(ruleArgs(r));
        CommandRunner.Result res = ufw(a);
        if (res.exitCode() != 0 || res.stdout().contains("ERROR")) {
            throw new FirewallException("ufw rejected the rule: " + FirewallBackend.firstLine(res.stdout()));
        }
    }

    @Override
    public void apply(FwRule r) throws FirewallException {
        CommandRunner.Result res = ufw(ruleArgs(r));
        if (res.exitCode() != 0 || res.stdout().contains("ERROR")) {
            throw new FirewallException("ufw failed: " + FirewallBackend.firstLine(res.stdout()));
        }
    }

    String status() throws FirewallException {
        CommandRunner.Result res = ufw(List.of("status", "numbered"));
        if (res.exitCode() != 0) throw new FirewallException("ufw status failed: " + FirewallBackend.firstLine(res.stdout()));
        if (res.stdout().contains("Status: inactive")) throw new FirewallException("UFW is inactive");
        return res.stdout();
    }

    @Override
    public Set<String> tags() throws FirewallException {
        Set<String> s = new HashSet<>();
        for (String line : status().split("\n")) {
            Matcher m = ANY_TAG.matcher(line.strip());
            if (m.find()) s.add(m.group(1));
        }
        return s;
    }

    @Override
    public boolean remove(String tag) throws FirewallException {
        boolean found = false;
        // Delete one at a time from a fresh numbered list (numbers shift after each delete).
        for (int guard = 0; guard < 10; guard++) {
            Integer num = null;
            for (String line : status().split("\n")) {
                Matcher m = NUMBERED.matcher(line.strip());
                if (m.matches() && m.group(2).equals(tag)) {
                    num = Integer.valueOf(m.group(1));
                    break;
                }
            }
            if (num == null) return found;
            CommandRunner.Result res = ufw(List.of("--force", "delete", num.toString()));
            if (res.exitCode() != 0) throw new FirewallException("ufw delete failed: " + FirewallBackend.firstLine(res.stdout()));
            found = true;
        }
        throw new FirewallException("ufw delete did not converge");
    }
}
