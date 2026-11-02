package gov.mot.netmon.agent.firewall;

import java.math.BigInteger;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An application access policy from the platform, re-validated locally. The application is matched
 * by an identity the operating system enforces (Linux account, Windows program or service); only
 * connections the application starts are affected.
 */
public record AppPolicy(UUID ruleId, String tag, String identityKind, String identity, String appMode, List<Dest> destinations,
        String mode, Instant expiresAt) {

    public record Dest(String cidr, String protocol, String port) {
    }

    static final Pattern LINUX_USER = Pattern.compile("^[a-z_][a-z0-9_-]{0,31}$");
    static final Pattern WINDOWS_PROGRAM = Pattern.compile("^[A-Za-z]:\\\\(?:[^\\\\/:*?\"<>|\\r\\n]{1,120}\\\\){0,12}[^\\\\/:*?\"<>|\\r\\n]{1,120}\\.exe$",
            Pattern.CASE_INSENSITIVE);
    static final Pattern WINDOWS_SERVICE = Pattern.compile("^[A-Za-z0-9_.$-]{1,128}$");
    public static final List<String> NON_INTERNET = List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
            "169.254.0.0/16", "127.0.0.0/8", "fc00::/7", "fe80::/10", "::1/128");

    public static AppPolicy parse(JsonNode p) {
        UUID id = UUID.fromString(p.path("ruleId").asText());
        String tag = p.path("tag").asText("");
        if (!FwRule.TAG.matcher(tag).matches()) throw new IllegalArgumentException("invalid tag");
        String kind = FwRule.oneOf(p.path("identityKind").asText(""), Set.of("LINUX_USER", "WINDOWS_PROGRAM", "WINDOWS_SERVICE"), "identity type");
        String identity = p.path("identity").asText("");
        Pattern pat = switch (kind) {
            case "LINUX_USER" -> LINUX_USER;
            case "WINDOWS_PROGRAM" -> WINDOWS_PROGRAM;
            default -> WINDOWS_SERVICE;
        };
        if (!pat.matcher(identity).matches()) throw new IllegalArgumentException("invalid application identity");
        String appMode = FwRule.oneOf(p.path("appMode").asText(""), Set.of("BLOCK_INTERNET", "ALLOW_ONLY", "BLOCK_DESTINATIONS"), "policy type");
        List<Dest> dests = new ArrayList<>();
        for (JsonNode d : p.path("destinations")) {
            if (dests.size() >= 50) throw new IllegalArgumentException("too many destinations");
            String cidr = FwRule.cidr(d.path("cidr").asText(""));
            String proto = FwRule.oneOf(d.path("protocol").asText("ANY"), Set.of("ANY", "TCP", "UDP"), "protocol");
            String port = d.hasNonNull("port") ? FwRule.port(d.get("port").asText()) : null;
            if (port != null && "ANY".equals(proto)) throw new IllegalArgumentException("ports need TCP or UDP");
            dests.add(new Dest(cidr, proto, port));
        }
        if (!"BLOCK_INTERNET".equals(appMode) && dests.isEmpty()) throw new IllegalArgumentException("no destinations");
        String mode = FwRule.oneOf(p.path("mode").asText("DRY_RUN"), Set.of("DRY_RUN", "ENFORCE"), "mode");
        Instant exp = p.hasNonNull("expiresAt") ? Instant.parse(p.get("expiresAt").asText()) : null;
        return new AppPolicy(id, tag, kind, identity, appMode, List.copyOf(dests), mode, exp);
    }

    /** Identities NetMon must never restrict: the whole system, the agent itself, core networking. */
    public String forbidden(String agentUser) {
        String v = identity.toLowerCase(Locale.ROOT);
        return switch (identityKind) {
            case "LINUX_USER" -> Set.of("root", "netmon-agent", "systemd-network", "systemd-resolve").contains(v)
                    || v.equals(agentUser) ? identity : null;
            case "WINDOWS_SERVICE" -> Set.of("netmon-agent", "mpssvc", "bfe", "dnscache", "dhcp", "nlasvc", "termservice", "winrm", "sshd")
                    .contains(v) ? identity : null;
            default -> v.endsWith("\\svchost.exe") || v.endsWith("\\lsass.exe") || v.endsWith("\\java.exe") || v.endsWith("\\javaw.exe")
                    || v.endsWith("\\netmon-agent.exe") ? identity : null;
        };
    }

    public String describe() {
        String what = switch (appMode) {
            case "BLOCK_INTERNET" -> "block internet access";
            case "ALLOW_ONLY" -> "allow only " + destinations.size() + " destination(s)";
            default -> "block " + destinations.size() + " destination(s)";
        };
        return identityKind + " " + identity + ": " + what;
    }

    /** The CIDRs the application may still reach (BLOCK_INTERNET / ALLOW_ONLY). */
    List<String> allowed() {
        List<String> out = new ArrayList<>("BLOCK_INTERNET".equals(appMode) ? NON_INTERNET : List.of("127.0.0.0/8", "::1/128"));
        destinations.forEach(d -> out.add(d.cidr()));
        return out;
    }

    /**
     * Address ranges NOT covered by the given CIDRs, as "first-last" strings, for Windows Firewall
     * (where block rules win over allow rules, "allow only X" must be written as "block everything else").
     */
    static List<String> complement(List<String> cidrs) {
        List<String> out = new ArrayList<>();
        for (int bits : new int[] {32, 128}) {
            List<BigInteger[]> ranges = new ArrayList<>();
            for (String c : cidrs) {
                boolean v6 = c.contains(":");
                if (v6 != (bits == 128)) continue;
                BigInteger[] r = Cidrs.range(c);
                if (r == null) continue;
                BigInteger fam = bits == 32 ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(200);
                ranges.add(new BigInteger[] {r[0].subtract(fam), r[1].subtract(fam)});
            }
            ranges.sort(Comparator.comparing(r -> r[0]));
            BigInteger next = BigInteger.ZERO;
            BigInteger max = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE);
            for (BigInteger[] r : ranges) {
                if (r[0].compareTo(next) > 0) out.add(fmt(next, bits) + "-" + fmt(r[0].subtract(BigInteger.ONE), bits));
                if (r[1].add(BigInteger.ONE).compareTo(next) > 0) next = r[1].add(BigInteger.ONE);
            }
            if (next.compareTo(max) <= 0) out.add(fmt(next, bits) + "-" + fmt(max, bits));
        }
        return out;
    }

    static String fmt(BigInteger v, int bits) {
        byte[] raw = v.toByteArray();
        byte[] b = new byte[bits / 8];
        int copy = Math.min(raw.length, b.length);
        System.arraycopy(raw, raw.length - copy, b, b.length - copy, copy);
        try {
            return Cidrs.format(InetAddress.getByAddress(b).getAddress());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
