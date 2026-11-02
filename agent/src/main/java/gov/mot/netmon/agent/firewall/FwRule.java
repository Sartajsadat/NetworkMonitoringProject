package gov.mot.netmon.agent.firewall;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A firewall rule received from the platform, re-validated locally. Only these typed fields reach
 * a firewall command, and each one must match a strict pattern: the platform cannot inject text.
 */
public record FwRule(UUID ruleId, String tag, String action, String direction, String remote, String local, String protocol,
        String localPort, String remotePort, String mode, Instant expiresAt) {

    public static final Pattern TAG = Pattern.compile("^[0-9a-f]{8,16}$");
    static final Pattern V4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})/(\\d{1,2})$");
    static final Pattern V6 = Pattern.compile("^[0-9a-f:]{2,39}/(\\d{1,3})$");
    static final Pattern PORT = Pattern.compile("^(\\d{1,5})(?:-(\\d{1,5}))?$");

    public boolean block() {
        return "BLOCK".equals(action);
    }

    public boolean inbound() {
        return "INBOUND".equals(direction);
    }

    public boolean v6() {
        return remote.contains(":");
    }

    public static FwRule parse(JsonNode p) {
        UUID id = UUID.fromString(p.path("ruleId").asText());
        String tag = p.path("tag").asText("");
        if (!TAG.matcher(tag).matches()) throw new IllegalArgumentException("invalid tag");
        String action = oneOf(p.path("action").asText(""), Set.of("ALLOW", "BLOCK"), "action");
        String direction = oneOf(p.path("direction").asText(""), Set.of("INBOUND", "OUTBOUND"), "direction");
        String protocol = oneOf(p.path("protocol").asText("ANY"), Set.of("ANY", "TCP", "UDP", "ICMP"), "protocol");
        String remote = cidr(p.path("remote").asText(""));
        String local = p.hasNonNull("local") ? cidr(p.get("local").asText()) : null;
        if (local != null && local.contains(":") != remote.contains(":")) throw new IllegalArgumentException("address families differ");
        String lp = p.hasNonNull("localPort") ? port(p.get("localPort").asText()) : null;
        String rp = p.hasNonNull("remotePort") ? port(p.get("remotePort").asText()) : null;
        if ((lp != null || rp != null) && !"TCP".equals(protocol) && !"UDP".equals(protocol)) {
            throw new IllegalArgumentException("ports need TCP or UDP");
        }
        String mode = oneOf(p.path("mode").asText("DRY_RUN"), Set.of("DRY_RUN", "ENFORCE"), "mode");
        Instant exp = p.hasNonNull("expiresAt") ? Instant.parse(p.get("expiresAt").asText()) : null;
        return new FwRule(id, tag, action, direction, remote, local, protocol, lp, rp, mode, exp);
    }

    static String oneOf(String v, Set<String> allowed, String what) {
        String u = v.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(u)) throw new IllegalArgumentException("invalid " + what);
        return u;
    }

    /** Literal addresses only (never a host name: no DNS lookup can happen). Host bits must be zero. */
    static String cidr(String raw) {
        String v = raw.trim().toLowerCase(Locale.ROOT);
        var m4 = V4.matcher(v);
        int prefix;
        int bits;
        if (m4.matches()) {
            for (int i = 1; i <= 4; i++) if (Integer.parseInt(m4.group(i)) > 255) throw new IllegalArgumentException("invalid address");
            prefix = Integer.parseInt(m4.group(5));
            bits = 32;
        } else if (V6.matcher(v).matches()) {
            prefix = Integer.parseInt(v.substring(v.indexOf('/') + 1));
            bits = 128;
        } else {
            throw new IllegalArgumentException("invalid address " + truncate(raw));
        }
        if (prefix > bits) throw new IllegalArgumentException("invalid prefix");
        try {
            byte[] addr = InetAddress.getByName(v.substring(0, v.indexOf('/'))).getAddress();
            if (addr.length * 8 != bits) throw new IllegalArgumentException("invalid address");
            if (!Cidrs.hostBitsZero(addr, prefix)) throw new IllegalArgumentException("host bits set in " + truncate(raw));
            return Cidrs.format(addr) + "/" + prefix;
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("invalid address");
        }
    }

    static String port(String raw) {
        var m = PORT.matcher(raw.trim());
        if (!m.matches()) throw new IllegalArgumentException("invalid port");
        int a = Integer.parseInt(m.group(1));
        int b = m.group(2) == null ? a : Integer.parseInt(m.group(2));
        if (a < 1 || b > 65535 || a > b) throw new IllegalArgumentException("invalid port");
        return a == b ? Integer.toString(a) : a + "-" + b;
    }

    static String truncate(String s) {
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    /** Human-readable form for logs and status messages. */
    public String describe() {
        StringBuilder b = new StringBuilder(block() ? "block " : "allow ").append(inbound() ? "from " : "to ").append(remote);
        if (!"ANY".equals(protocol)) b.append(' ').append(protocol);
        if (localPort != null) b.append(" port ").append(localPort);
        if (remotePort != null) b.append(" remote port ").append(remotePort);
        return b.toString();
    }
}
