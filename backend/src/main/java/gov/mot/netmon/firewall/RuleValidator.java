package gov.mot.netmon.firewall;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gov.mot.netmon.common.Cidr;
import gov.mot.netmon.common.IpAddresses;

/**
 * Validation and safety checks for IP allow/block rules, and the preview of the exact command each
 * firewall backend would run. Pure functions: easy to test, used for the preview, the request and
 * again at approval time.
 */
public final class RuleValidator {

    static final Set<String> ACTIONS = Set.of("ALLOW", "BLOCK");
    static final Set<String> DIRECTIONS = Set.of("INBOUND", "OUTBOUND");
    static final Set<String> PROTOCOLS = Set.of("ANY", "TCP", "UDP", "ICMP");
    static final Pattern PORT = Pattern.compile("^(\\d{1,5})(?:-(\\d{1,5}))?$");
    static final int[] MANAGEMENT_PORTS = {22, 3389, 5985, 5986};
    public static final Duration MAX_EXPIRY = Duration.ofDays(365);

    private RuleValidator() {
    }

    public record Input(String action, String direction, String remote, String local, String protocol, String localPort,
            String remotePort) {
    }

    /** One finding: a stable code (translated by the UI), an optional parameter and the English text. */
    public record Issue(String code, String param, String text) {
    }

    /** Result of checking a rule: errors refuse it; warnings must be acknowledged by the requester. */
    public record Check(RuleSpec spec, List<Issue> errorIssues, List<Issue> warningIssues) {
        public boolean ok() {
            return errorIssues.isEmpty();
        }

        public List<String> errors() {
            return errorIssues.stream().map(Issue::text).toList();
        }

        public List<String> warnings() {
            return warningIssues.stream().map(Issue::text).toList();
        }
    }

    static void add(List<Issue> list, String code, String param, String text) {
        list.add(new Issue(code, param, text));
    }

    static String cidr(String raw, String what, List<Issue> errors) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        try {
            if (!v.contains("/")) {
                IpAddresses.ParsedIp ip = IpAddresses.parse(v);
                return ip.canonical() + (ip.version() == 4 ? "/32" : "/128");
            }
            return Cidr.parse(v).network();
        } catch (IllegalArgumentException e) {
            add(errors, "ADDRESS", raw.trim(), what + ": " + e.getMessage());
            return null;
        }
    }

    static String port(String raw, String what, List<Issue> errors) {
        if (raw == null || raw.isBlank()) return null;
        Matcher m = PORT.matcher(raw.trim());
        if (!m.matches()) {
            add(errors, "PORT", raw.trim(), what + " must be a number or a range like 8000-8080");
            return null;
        }
        int from = Integer.parseInt(m.group(1));
        int to = m.group(2) == null ? from : Integer.parseInt(m.group(2));
        if (from < 1 || to > 65535 || from > to) {
            add(errors, "PORT", raw.trim(), what + " must be between 1 and 65535");
            return null;
        }
        return from == to ? Integer.toString(from) : from + "-" + to;
    }

    static boolean portCovers(String port, int p) {
        if (port == null) return true;
        String[] r = port.split("-");
        int from = Integer.parseInt(r[0]);
        int to = r.length > 1 ? Integer.parseInt(r[1]) : from;
        return p >= from && p <= to;
    }

    static boolean overlaps(String a, String b) {
        Cidr x = Cidr.parse(a);
        Cidr y = Cidr.parse(b);
        return x.version() == y.version() && x.first().compareTo(y.last()) <= 0 && y.first().compareTo(x.last()) <= 0;
    }

    static boolean everything(String cidr) {
        return cidr.endsWith("/0");
    }

    /**
     * @param protectedCidrs addresses that must never be blocked (platform, management, monitoring)
     * @param requesterIp    address the requester uses right now (may be null)
     * @param dryRun         the target only previews; nothing will change
     */
    public static Check check(Input in, Instant expiresAt, Instant now, List<String> protectedCidrs, String requesterIp, boolean dryRun) {
        List<Issue> errors = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();
        String action = in.action() == null ? "" : in.action().trim().toUpperCase(Locale.ROOT);
        String direction = in.direction() == null ? "" : in.direction().trim().toUpperCase(Locale.ROOT);
        String protocol = in.protocol() == null || in.protocol().isBlank() ? "ANY" : in.protocol().trim().toUpperCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) add(errors, "ACTION", null, "Action must be ALLOW or BLOCK");
        if (!DIRECTIONS.contains(direction)) add(errors, "DIRECTION", null, "Direction must be INBOUND or OUTBOUND");
        if (!PROTOCOLS.contains(protocol)) add(errors, "PROTOCOL", null, "Protocol must be ANY, TCP, UDP or ICMP");
        String remote = cidr(in.remote(), "Remote address", errors);
        if (remote == null && (in.remote() == null || in.remote().isBlank())) add(errors, "REMOTE_REQUIRED", null, "Remote address is required");
        String local = cidr(in.local(), "Local address", errors);
        String lp = port(in.localPort(), "Local port", errors);
        String rp = port(in.remotePort(), "Remote port", errors);
        if ((lp != null || rp != null) && !"TCP".equals(protocol) && !"UDP".equals(protocol)) {
            add(errors, "PORT_PROTOCOL", null, "Ports can only be used with TCP or UDP");
        }
        if (remote != null && local != null && Cidr.parse(remote).version() != Cidr.parse(local).version()) {
            add(errors, "FAMILY_MISMATCH", null, "Remote and local addresses must both be IPv4 or both IPv6");
        }
        if (expiresAt != null) {
            if (!expiresAt.isAfter(now)) add(errors, "EXPIRY_PAST", null, "Expiry must be in the future");
            else if (expiresAt.isAfter(now.plus(MAX_EXPIRY))) add(errors, "EXPIRY_TOO_FAR", null, "Expiry may be at most 365 days ahead");
        }
        if (!errors.isEmpty()) return new Check(null, errors, warnings);

        RuleSpec spec = new RuleSpec(action, direction, remote, local, protocol, lp, rp);
        if (spec.block()) {
            if (everything(remote) && lp == null && rp == null) {
                add(errors, "BLOCK_ALL", null, "Blocking every address on every port would cut the target off. Narrow the address range or give a port.");
            }
            for (String p : protectedCidrs) {
                try {
                    if (overlaps(remote, p)) {
                        add(errors, "PROTECTED", p, "The rule would block a protected management/monitoring address (" + p + ")");
                    }
                } catch (IllegalArgumentException e) {
                    // ignore malformed protected entries
                }
            }
            if (requesterIp != null) {
                try {
                    String mine = cidr(requesterIp, "requester", new ArrayList<Issue>());
                    if (mine != null && overlaps(remote, mine)) {
                        add(warnings, "OWN_ADDRESS", requesterIp, "The rule covers the address you are connected from (" + requesterIp + ")");
                    }
                } catch (IllegalArgumentException e) {
                    // not an IP literal
                }
            }
            if (spec.inbound() && ("TCP".equals(protocol) || "ANY".equals(protocol))) {
                Cidr r = Cidr.parse(remote);
                boolean wide = r.version() == 4 ? r.prefix() < 16 : r.prefix() < 48;
                for (int mp : MANAGEMENT_PORTS) {
                    if (wide && portCovers(lp, mp)) {
                        add(warnings, "MANAGEMENT_PORT", Integer.toString(mp), "The rule blocks remote-management port " + mp + " (SSH/RDP/WinRM) for a large address range");
                        break;
                    }
                }
            }
            if (expiresAt == null) add(warnings, "NO_EXPIRY", null, "No expiry: the block stays until someone removes it");
        } else {
            add(warnings, "ALLOW_SCOPE", null, "Allow rules here are exceptions to NetMon block rules; they do not open ports closed by other firewall rules");
        }
        if (dryRun) add(warnings, "DRY_RUN", null, "Dry run: the target is in dry-run mode, nothing will be changed");
        return new Check(spec, errors, warnings);
    }

    // ------------------------------------------------------------------ previews

    static boolean v6(String cidr) {
        return cidr.contains(":");
    }

    /** The nftables rule the agent adds to its own table (inet netmon). */
    public static String nftables(RuleSpec s, String tag) {
        StringBuilder b = new StringBuilder(s.block() ? "add rule inet netmon " : "insert rule inet netmon ");
        b.append(s.inbound() ? "input" : "output");
        String fam = v6(s.remoteCidr()) ? "ip6" : "ip";
        b.append(' ').append(fam).append(s.inbound() ? " saddr " : " daddr ").append(s.remoteCidr());
        if (s.localCidr() != null) b.append(' ').append(fam).append(s.inbound() ? " daddr " : " saddr ").append(s.localCidr());
        switch (s.protocol()) {
            case "TCP", "UDP" -> {
                String p = s.protocol().toLowerCase(Locale.ROOT);
                String lpField = s.inbound() ? "dport" : "sport";
                String rpField = s.inbound() ? "sport" : "dport";
                if (s.localPort() == null && s.remotePort() == null) b.append(" meta l4proto ").append(p);
                if (s.localPort() != null) b.append(' ').append(p).append(' ').append(lpField).append(' ').append(s.localPort());
                if (s.remotePort() != null) b.append(' ').append(p).append(' ').append(rpField).append(' ').append(s.remotePort());
            }
            case "ICMP" -> b.append(" meta l4proto ").append(v6(s.remoteCidr()) ? "ipv6-icmp" : "icmp");
            default -> {
            }
        }
        b.append(" counter ").append(s.block() ? "drop" : "accept").append(" comment \"netmon:").append(tag).append('"');
        return b.toString();
    }

    /** The UFW command (UFW applies the first matching rule, so NetMon rules are inserted at the top). */
    public static String ufw(RuleSpec s, String tag) {
        StringBuilder b = new StringBuilder("ufw insert 1 ").append(s.block() ? "deny" : "allow");
        if (!s.inbound()) b.append(" out");
        if (!"ANY".equals(s.protocol())) b.append(" proto ").append(s.protocol().toLowerCase(Locale.ROOT));
        String local = s.localCidr() == null ? "any" : s.localCidr();
        if (s.inbound()) {
            b.append(" from ").append(s.remoteCidr());
            if (s.remotePort() != null) b.append(" port ").append(s.remotePort().replace('-', ':'));
            b.append(" to ").append(local);
            if (s.localPort() != null) b.append(" port ").append(s.localPort().replace('-', ':'));
        } else {
            b.append(" from ").append(local);
            if (s.localPort() != null) b.append(" port ").append(s.localPort().replace('-', ':'));
            b.append(" to ").append(s.remoteCidr());
            if (s.remotePort() != null) b.append(" port ").append(s.remotePort().replace('-', ':'));
        }
        return b.append(" comment 'netmon:").append(tag).append('\'').toString();
    }

    /** The Windows Defender Firewall cmdlet. */
    public static String windows(RuleSpec s, String tag) {
        StringBuilder b = new StringBuilder("New-NetFirewallRule -Name netmon-").append(tag).append(" -Group NetMon -Direction ")
                .append(s.inbound() ? "Inbound" : "Outbound").append(" -Action ").append(s.block() ? "Block" : "Allow")
                .append(" -RemoteAddress ").append(s.remoteCidr());
        if (s.localCidr() != null) b.append(" -LocalAddress ").append(s.localCidr());
        if (!"ANY".equals(s.protocol())) b.append(" -Protocol ").append("ICMP".equals(s.protocol())
                ? (v6(s.remoteCidr()) ? "ICMPv6" : "ICMPv4") : s.protocol());
        if (s.localPort() != null) b.append(" -LocalPort ").append(s.localPort());
        if (s.remotePort() != null) b.append(" -RemotePort ").append(s.remotePort());
        return b.toString();
    }

    /** Vendor-neutral description, for equipment without an adapter. */
    public static String neutral(RuleSpec s) {
        StringBuilder b = new StringBuilder(s.block() ? "DENY " : "PERMIT ").append(s.inbound() ? "inbound" : "outbound");
        b.append(" protocol=").append(s.protocol().toLowerCase(Locale.ROOT));
        b.append(s.inbound() ? " source=" : " destination=").append(s.remoteCidr());
        if (s.localCidr() != null) b.append(s.inbound() ? " destination=" : " source=").append(s.localCidr());
        if (s.localPort() != null) b.append(" local-port=").append(s.localPort());
        if (s.remotePort() != null) b.append(" remote-port=").append(s.remotePort());
        return b.toString();
    }

    public static String preview(RuleSpec s, String tag, String backend) {
        return switch (backend == null ? "" : backend) {
            case "NFTABLES" -> "nft " + nftables(s, tag);
            case "UFW" -> ufw(s, tag);
            case "WINDOWS" -> windows(s, tag);
            case "CENTRAL" -> "(no adapter for this equipment: nothing is sent to it)\nChange for the device administrators: " + neutral(s);
            default -> "(no supported firewall detected on the target: dry run only)\n" + "nft " + nftables(s, tag);
        };
    }
}
