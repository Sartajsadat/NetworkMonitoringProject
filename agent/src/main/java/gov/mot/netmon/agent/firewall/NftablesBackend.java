package gov.mot.netmon.agent.firewall;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.agent.collect.CommandRunner;
import gov.mot.netmon.agent.model.Json;

/**
 * nftables: NetMon rules live in their OWN table, {@code inet netmon}, with input/output chains at
 * priority -10 and policy accept. Nothing outside that table is ever changed, so the server's own
 * rules (and Docker's) stay as they are. Recovery: {@code nft delete table inet netmon}.
 *
 * <p>Changes are written as a small nft script (built only from validated fields) and loaded with
 * {@code nft -f}, which applies it atomically; {@code nft -c -f} checks it without changing anything.
 */
public class NftablesBackend implements FirewallBackend {

    static final Pattern COMMENT = Pattern.compile("^netmon:([0-9a-f]{8,16})$");

    private final CommandRunner nft;
    private final Path workDir;
    private final String problem;

    public NftablesBackend(CommandRunner nft, Path workDir, String problem) {
        this.nft = nft;
        this.workDir = workDir;
        this.problem = problem;
    }

    @Override
    public String name() {
        return "NFTABLES";
    }

    @Override
    public String problem() {
        return problem;
    }

    static final String TABLE = """
            table inet netmon {
            \tchain input { type filter hook input priority -10; policy accept; }
            \tchain output { type filter hook output priority -10; policy accept; }
            }
            """;

    /** The rule statement, e.g. {@code add rule inet netmon input ip saddr 203.0.113.0/24 tcp dport 443 counter drop comment "netmon:ab12cd34"}. */
    public static String rule(FwRule r) {
        StringBuilder b = new StringBuilder(r.block() ? "add rule inet netmon " : "insert rule inet netmon ");
        b.append(r.inbound() ? "input" : "output");
        String fam = r.v6() ? "ip6" : "ip";
        b.append(' ').append(fam).append(r.inbound() ? " saddr " : " daddr ").append(r.remote());
        if (r.local() != null) b.append(' ').append(fam).append(r.inbound() ? " daddr " : " saddr ").append(r.local());
        switch (r.protocol()) {
            case "TCP", "UDP" -> {
                String p = r.protocol().toLowerCase(Locale.ROOT);
                if (r.localPort() == null && r.remotePort() == null) b.append(" meta l4proto ").append(p);
                if (r.localPort() != null) b.append(' ').append(p).append(r.inbound() ? " dport " : " sport ").append(r.localPort());
                if (r.remotePort() != null) b.append(' ').append(p).append(r.inbound() ? " sport " : " dport ").append(r.remotePort());
            }
            case "ICMP" -> b.append(" meta l4proto ").append(r.v6() ? "ipv6-icmp" : "icmp");
            default -> {
            }
        }
        return b.append(" counter ").append(r.block() ? "drop" : "accept").append(" comment \"netmon:").append(r.tag()).append('"')
                .toString();
    }

    @Override
    public String describe(FwRule r) {
        return "nft " + rule(r);
    }

    @Override
    public List<String> applicationControl() {
        return problem == null ? List.of("LINUX_USER") : List.of();
    }

    static String dest(AppPolicy.Dest d) {
        StringBuilder b = new StringBuilder(d.cidr().contains(":") ? "ip6 daddr " : "ip daddr ").append(d.cidr());
        if (!"ANY".equals(d.protocol())) {
            String p = d.protocol().toLowerCase(Locale.ROOT);
            b.append(d.port() == null ? " meta l4proto " + p : " " + p + " dport " + d.port());
        }
        return b.toString();
    }

    /**
     * One chain per policy (app_&lt;tag&gt;), entered from the output chain for packets of sockets owned
     * by the application's account. Replies to connections others opened to the application pass.
     */
    public static String appBatch(AppPolicy p) {
        if (!"LINUX_USER".equals(p.identityKind())) throw new IllegalArgumentException("nftables matches Linux users only");
        String chain = "app_" + p.tag();
        String r = "add rule inet netmon " + chain + " ";
        StringBuilder b = new StringBuilder();
        b.append("add chain inet netmon ").append(chain).append('\n');
        b.append(r).append("ct direction reply accept\n");
        if ("BLOCK_DESTINATIONS".equals(p.appMode())) {
            p.destinations().forEach(d -> b.append(r).append(dest(d)).append(" counter drop\n"));
        } else {
            for (String c : p.allowed().subList(0, p.allowed().size() - p.destinations().size())) {
                b.append(r).append(c.contains(":") ? "ip6 daddr " : "ip daddr ").append(c).append(" accept\n");
            }
            p.destinations().forEach(d -> b.append(r).append(dest(d)).append(" accept\n"));
            b.append(r).append("counter drop\n");
        }
        // The jump goes last so the chain is complete before any packet can enter it.
        b.append("add rule inet netmon output meta skuid \"").append(p.identity()).append("\" jump ").append(chain)
                .append(" comment \"netmon:").append(p.tag()).append("\"\n");
        return b.toString();
    }

    @Override
    public String describeApp(AppPolicy p) {
        return "nft -f - <<EOF\n" + appBatch(p) + "EOF";
    }

    @Override
    public void checkApp(AppPolicy p) throws FirewallException {
        CommandRunner.Result res = loadText(TABLE + appBatch(p), true);
        if (res.exitCode() != 0) throw new FirewallException("nft rejected the policy: " + FirewallBackend.firstLine(res.stdout()));
    }

    @Override
    public void applyApp(AppPolicy p) throws FirewallException {
        CommandRunner.Result res = loadText(TABLE + appBatch(p), false);
        if (res.exitCode() != 0) throw new FirewallException("nft failed: " + FirewallBackend.firstLine(res.stdout()));
    }

    private CommandRunner.Result load(FwRule r, boolean checkOnly) throws FirewallException {
        return loadText(TABLE + rule(r) + "\n", checkOnly);
    }

    private CommandRunner.Result loadText(String script, boolean checkOnly) throws FirewallException {
        Path file = null;
        try {
            Files.createDirectories(workDir);
            file = Files.createTempFile(workDir, "netmon-", ".nft");
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system
            }
            Files.writeString(file, script, StandardCharsets.UTF_8);
            List<String> args = new ArrayList<>();
            if (checkOnly) args.add("-c");
            args.add("-f");
            args.add(file.toString());
            return nft.run(args);
        } catch (IOException e) {
            throw new FirewallException("nft could not run: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FirewallException("interrupted");
        } finally {
            if (file != null) try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // temp file
            }
        }
    }

    @Override
    public void check(FwRule r) throws FirewallException {
        CommandRunner.Result res = load(r, true);
        if (res.exitCode() != 0) throw new FirewallException("nft rejected the rule: " + FirewallBackend.firstLine(res.stdout()));
    }

    @Override
    public void apply(FwRule r) throws FirewallException {
        CommandRunner.Result res = load(r, false);
        if (res.exitCode() != 0) throw new FirewallException("nft failed: " + FirewallBackend.firstLine(res.stdout()));
    }

    record Listed(String chain, long handle, String tag) {
    }

    /** Names of the chains in table inet netmon. */
    Set<String> chains() throws FirewallException {
        Set<String> out = new HashSet<>();
        try {
            CommandRunner.Result res = nft.run(List.of("-j", "list", "table", "inet", "netmon"));
            if (res.exitCode() != 0) return out;
            for (JsonNode item : Json.MAPPER.readTree(res.stdout()).path("nftables")) {
                JsonNode c = item.get("chain");
                if (c != null && "netmon".equals(c.path("table").asText())) out.add(c.path("name").asText());
            }
        } catch (IOException e) {
            throw new FirewallException("nft could not run: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FirewallException("interrupted");
        }
        return out;
    }

    List<Listed> list() throws FirewallException {
        try {
            CommandRunner.Result res = nft.run(List.of("-j", "list", "table", "inet", "netmon"));
            if (res.exitCode() != 0) {
                if (res.stdout().contains("No such file or directory")) return List.of(); // table not created yet
                throw new FirewallException("nft list failed: " + FirewallBackend.firstLine(res.stdout()));
            }
            return parse(res.stdout());
        } catch (IOException e) {
            throw new FirewallException("nft could not run: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FirewallException("interrupted");
        }
    }

    static List<Listed> parse(String json) throws FirewallException {
        List<Listed> out = new ArrayList<>();
        try {
            JsonNode root = Json.MAPPER.readTree(json);
            for (JsonNode item : root.path("nftables")) {
                JsonNode rule = item.get("rule");
                if (rule == null || !"netmon".equals(rule.path("table").asText())) continue;
                Matcher m = COMMENT.matcher(rule.path("comment").asText(""));
                if (m.matches()) out.add(new Listed(rule.path("chain").asText(), rule.path("handle").asLong(), m.group(1)));
            }
        } catch (IOException e) {
            throw new FirewallException("unreadable nft output");
        }
        return out;
    }

    @Override
    public Set<String> tags() throws FirewallException {
        Set<String> s = new HashSet<>();
        for (Listed l : list()) s.add(l.tag());
        return s;
    }

    @Override
    public boolean remove(String tag) throws FirewallException {
        boolean found = false;
        for (Listed l : list()) {
            if (!l.tag().equals(tag)) continue;
            if (!"input".equals(l.chain()) && !"output".equals(l.chain())) continue;
            found = true;
            try {
                CommandRunner.Result res = nft.run(List.of("delete", "rule", "inet", "netmon", l.chain(), "handle", Long.toString(l.handle())));
                if (res.exitCode() != 0) throw new FirewallException("nft delete failed: " + FirewallBackend.firstLine(res.stdout()));
            } catch (IOException e) {
                throw new FirewallException("nft could not run: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FirewallException("interrupted");
            }
        }
        // An application policy also has its own chain; it can go once nothing jumps to it.
        if (chains().contains("app_" + tag)) {
            found = true;
            try {
                CommandRunner.Result res = nft.run(List.of("delete", "chain", "inet", "netmon", "app_" + tag));
                if (res.exitCode() != 0) throw new FirewallException("nft delete chain failed: " + FirewallBackend.firstLine(res.stdout()));
            } catch (IOException e) {
                throw new FirewallException("nft could not run: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FirewallException("interrupted");
            }
        }
        return found;
    }
}
