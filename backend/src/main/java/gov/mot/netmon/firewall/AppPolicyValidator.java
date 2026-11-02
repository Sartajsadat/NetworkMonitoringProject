package gov.mot.netmon.firewall;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import gov.mot.netmon.firewall.RuleValidator.Issue;

/**
 * Validation, safety checks and preview for application access policies. Pure functions.
 *
 * <p>An application is matched by an identity the operating system enforces: the Linux account its
 * processes run as, or a Windows program path or service. Policies only affect connections the
 * application STARTS (outbound); replies to connections other systems make to it are not touched.
 */
public final class AppPolicyValidator {

    public static final Set<String> MODES = Set.of("BLOCK_INTERNET", "ALLOW_ONLY", "BLOCK_DESTINATIONS");
    public static final Set<String> KINDS = Set.of("LINUX_USER", "WINDOWS_PROGRAM", "WINDOWS_SERVICE");
    static final Pattern LINUX_USER = Pattern.compile("^[a-z_][a-z0-9_-]{0,31}$");
    static final Pattern WINDOWS_PROGRAM = Pattern.compile("^[A-Za-z]:\\\\(?:[^\\\\/:*?\"<>|\\r\\n]{1,120}\\\\){0,12}[^\\\\/:*?\"<>|\\r\\n]{1,120}\\.exe$",
            Pattern.CASE_INSENSITIVE);
    static final Pattern WINDOWS_SERVICE = Pattern.compile("^[A-Za-z0-9_.$-]{1,128}$");
    /** Destinations that are not "the internet": private, shared, link-local and loopback ranges. */
    public static final List<String> NON_INTERNET = List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
            "169.254.0.0/16", "127.0.0.0/8", "fc00::/7", "fe80::/10", "::1/128");
    public static final int MAX_DESTINATIONS = 50;

    private AppPolicyValidator() {
    }

    public record Destination(String cidr, String protocol, String port) {
    }

    public record Check(List<Destination> destinations, List<Issue> errorIssues, List<Issue> warningIssues) {
        public boolean ok() {
            return errorIssues.isEmpty();
        }
    }

    /** Identity validation for the application inventory. Returns null if valid, else the reason. */
    public static String identityProblem(String kind, String value, String serverOs) {
        if (kind == null || !KINDS.contains(kind)) return "Identity type must be LINUX_USER, WINDOWS_PROGRAM or WINDOWS_SERVICE";
        if (value == null || value.isBlank()) return "Give the identity value";
        boolean linux = kind.startsWith("LINUX");
        if (serverOs != null && linux != "LINUX".equals(serverOs)) {
            return "This identity type does not exist on a " + serverOs + " server";
        }
        return switch (kind) {
            case "LINUX_USER" -> LINUX_USER.matcher(value).matches() ? null : "Not a valid Linux account name";
            case "WINDOWS_PROGRAM" -> WINDOWS_PROGRAM.matcher(value).matches() ? null : "Give the full path of the .exe, e.g. C:\\Program Files\\App\\app.exe";
            default -> WINDOWS_SERVICE.matcher(value).matches() ? null : "Not a valid Windows service name";
        };
    }

    /** Identities that may never be restricted: they would cut the server or the agent off. */
    static String forbiddenIdentity(String kind, String value) {
        String v = value.toLowerCase(Locale.ROOT);
        if ("LINUX_USER".equals(kind) && Set.of("root", "netmon-agent", "systemd-network", "systemd-resolve").contains(v)) {
            return value;
        }
        if ("WINDOWS_SERVICE".equals(kind) && Set.of("netmon-agent", "mpssvc", "bfe", "dnscache", "dhcp", "nlasvc", "termservice",
                "winrm", "sshd").contains(v)) {
            return value;
        }
        if ("WINDOWS_PROGRAM".equals(kind) && (v.endsWith("\\svchost.exe") || v.endsWith("\\lsass.exe") || v.endsWith("\\java.exe")
                || v.endsWith("\\javaw.exe") || v.endsWith("\\netmon-agent.exe"))) {
            return value;
        }
        return null;
    }

    public static Check check(String mode, String identityKind, String identityValue, List<Destination> in, Instant expiresAt,
            Instant now, boolean dryRun) {
        List<Issue> errors = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();
        List<Destination> out = new ArrayList<>();
        if (mode == null || !MODES.contains(mode)) RuleValidator.add(errors, "APP_MODE", null, "Choose a policy type");
        String forbidden = forbiddenIdentity(identityKind, identityValue);
        if (forbidden != null) {
            RuleValidator.add(errors, "APP_FORBIDDEN_IDENTITY", forbidden, "Restricting " + forbidden
                    + " could cut off the server, its management or the monitoring agent");
        }
        List<Destination> list = in == null ? List.of() : in;
        if (list.size() > MAX_DESTINATIONS) RuleValidator.add(errors, "APP_TOO_MANY", Integer.toString(MAX_DESTINATIONS),
                "At most " + MAX_DESTINATIONS + " destinations");
        boolean windows = identityKind != null && identityKind.startsWith("WINDOWS");
        for (Destination d : list.stream().limit(MAX_DESTINATIONS).toList()) {
            if (d.cidr() == null || d.cidr().isBlank()) {
                RuleValidator.add(errors, "REMOTE_REQUIRED", null, "Each destination needs an address or network");
                continue;
            }
            String cidr = RuleValidator.cidr(d.cidr(), "Destination", errors);
            String proto = d.protocol() == null || d.protocol().isBlank() ? "ANY" : d.protocol().trim().toUpperCase(Locale.ROOT);
            if (!Set.of("ANY", "TCP", "UDP").contains(proto)) {
                RuleValidator.add(errors, "PROTOCOL", null, "Protocol must be ANY, TCP or UDP");
                continue;
            }
            String port = RuleValidator.port(d.port(), "Port", errors);
            if (port != null && "ANY".equals(proto)) RuleValidator.add(errors, "PORT_PROTOCOL", null, "Ports can only be used with TCP or UDP");
            if (cidr != null) out.add(new Destination(cidr, proto, port));
        }
        if ("ALLOW_ONLY".equals(mode) || "BLOCK_DESTINATIONS".equals(mode)) {
            if (list.isEmpty()) RuleValidator.add(errors, "APP_NEED_DESTINATIONS", null, "List at least one destination");
        }
        if ("ALLOW_ONLY".equals(mode) && windows && out.stream().anyMatch(d -> !"ANY".equals(d.protocol()))) {
            RuleValidator.add(errors, "APP_WINDOWS_PORTS", null,
                    "On Windows an allow-only policy can limit addresses but not ports (Windows Firewall block rules win over allow rules)");
        }
        if (expiresAt != null) {
            if (!expiresAt.isAfter(now)) RuleValidator.add(errors, "EXPIRY_PAST", null, "Expiry must be in the future");
            else if (expiresAt.isAfter(now.plus(RuleValidator.MAX_EXPIRY))) RuleValidator.add(errors, "EXPIRY_TOO_FAR", null,
                    "Expiry may be at most 365 days ahead");
        }
        if (!errors.isEmpty()) return new Check(out, errors, warnings);
        if ("LINUX_USER".equals(identityKind)) RuleValidator.add(warnings, "APP_SHARED_USER", identityValue,
                "The policy applies to every process running as " + identityValue + ", not only this application");
        if ("ALLOW_ONLY".equals(mode) && out.stream().noneMatch(d -> "53".equals(d.port()) || "ANY".equals(d.protocol()))) {
            RuleValidator.add(warnings, "APP_NO_DNS", null,
                    "No DNS server is allowed: the application cannot look up names unless it uses addresses only");
        }
        if ("BLOCK_INTERNET".equals(mode)) RuleValidator.add(warnings, "APP_PRIVATE_OK", null,
                "Private, shared and loopback networks stay reachable; listed destinations are extra exceptions");
        if (expiresAt == null) RuleValidator.add(warnings, "NO_EXPIRY", null, "No expiry: the policy stays until someone removes it");
        if (dryRun) RuleValidator.add(warnings, "DRY_RUN", null, "Dry run: the target is in dry-run mode, nothing will be changed");
        return new Check(out, errors, warnings);
    }

    // ------------------------------------------------------------------ preview

    static String match(String kind, String value) {
        return switch (kind) {
            case "LINUX_USER" -> "meta skuid \"" + value + "\"";
            case "WINDOWS_PROGRAM" -> "-Program \"" + value + "\"";
            default -> "-Service " + value;
        };
    }

    static String nftDest(Destination d) {
        StringBuilder b = new StringBuilder(d.cidr().contains(":") ? "ip6 daddr " : "ip daddr ").append(d.cidr());
        if (!"ANY".equals(d.protocol())) {
            String p = d.protocol().toLowerCase(Locale.ROOT);
            b.append(d.port() == null ? " meta l4proto " + p : " " + p + " dport " + d.port());
        }
        return b.toString();
    }

    /** The nftables batch the Linux agent loads (one chain per policy, entered from the output chain). */
    public static String nftables(String mode, String user, List<Destination> dests, String tag) {
        String chain = "app_" + tag;
        StringBuilder b = new StringBuilder();
        b.append("add chain inet netmon ").append(chain).append('\n');
        b.append("add rule inet netmon output meta skuid \"").append(user).append("\" jump ").append(chain)
                .append(" comment \"netmon:").append(tag).append("\"\n");
        String r = "add rule inet netmon " + chain + " ";
        b.append(r).append("ct direction reply accept\n");
        switch (mode) {
            case "BLOCK_DESTINATIONS" -> dests.forEach(d -> b.append(r).append(nftDest(d)).append(" counter drop\n"));
            default -> {
                if ("BLOCK_INTERNET".equals(mode)) {
                    NON_INTERNET.forEach(c -> b.append(r).append(c.contains(":") ? "ip6 daddr " : "ip daddr ").append(c).append(" accept\n"));
                } else {
                    b.append(r).append("ip daddr 127.0.0.0/8 accept\n").append(r).append("ip6 daddr ::1/128 accept\n");
                }
                dests.forEach(d -> b.append(r).append(nftDest(d)).append(" accept\n"));
                b.append(r).append("counter drop\n");
            }
        }
        return b.toString().trim();
    }

    /** Windows Defender Firewall rules (block rules win over allow rules there, so "allow only" is a block of everything else). */
    public static String windows(String mode, String kind, String value, List<Destination> dests, String tag) {
        String base = "New-NetFirewallRule -Group NetMon -Direction Outbound -Action Block " + match(kind, value);
        StringBuilder b = new StringBuilder();
        switch (mode) {
            case "BLOCK_DESTINATIONS" -> {
                int n = 1;
                for (Destination d : dests) {
                    b.append(base).append(" -Name netmon-").append(tag).append('-').append(n++).append(" -RemoteAddress ").append(d.cidr());
                    if (!"ANY".equals(d.protocol())) b.append(" -Protocol ").append(d.protocol());
                    if (d.port() != null) b.append(" -RemotePort ").append(d.port());
                    b.append('\n');
                }
            }
            default -> {
                List<String> allowed = new ArrayList<>("BLOCK_INTERNET".equals(mode) ? NON_INTERNET : List.of("127.0.0.0/8", "::1/128"));
                dests.forEach(d -> allowed.add(d.cidr()));
                b.append(base).append(" -Name netmon-").append(tag).append("-1 -RemoteAddress <every address except ")
                        .append(String.join(", ", allowed)).append(">\n");
            }
        }
        return b.toString().trim();
    }

    public static String preview(String mode, String kind, String value, List<Destination> dests, String tag, String backend) {
        if ("LINUX_USER".equals(kind)) {
            String batch = nftables(mode, value, dests, tag);
            return "NFTABLES".equals(backend) ? "nft -f - <<EOF\n" + batch + "\nEOF" : "(no nftables on the target: not enforceable)\n" + batch;
        }
        return windows(mode, kind, value, dests, tag);
    }
}
