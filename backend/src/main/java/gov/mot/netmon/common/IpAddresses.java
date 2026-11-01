package gov.mot.netmon.common;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Strict IP literal parsing. Never performs DNS lookups: input that is not a literal
 * IPv4/IPv6 address is rejected before it reaches {@link InetAddress}.
 */
public final class IpAddresses {

    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private IpAddresses() {
    }

    /** Parsed, canonical address. */
    public record ParsedIp(String canonical, int version) {
    }

    public static ParsedIp parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("IP address is required");
        }
        String value = raw.trim();
        if (IPV4.matcher(value).matches()) {
            return new ParsedIp(value, 4);
        }
        if (value.contains(":") && IPV6_CHARS.matcher(value).matches() && value.length() <= 45) {
            try {
                InetAddress addr = InetAddress.getByName(value); // literal only: no DNS for ':'-containing input
                if (addr instanceof Inet6Address) {
                    return new ParsedIp(compressIpv6(addr.getHostAddress()), 6);
                }
                if (addr instanceof Inet4Address) { // IPv4-mapped form like ::ffff:10.0.0.1
                    return new ParsedIp(addr.getHostAddress(), 4);
                }
            } catch (UnknownHostException e) {
                // fall through
            }
        }
        throw new IllegalArgumentException("'" + raw + "' is not a valid IPv4 or IPv6 address");
    }

    public static boolean isValid(String raw) {
        try {
            parse(raw);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Converts Java's full IPv6 text (1:0:0:0:0:0:0:1) to RFC 5952 compressed form (1::1). */
    static String compressIpv6(String full) {
        String[] groups = full.split("%")[0].split(":");
        int bestStart = -1;
        int bestLen = 0;
        for (int i = 0; i < groups.length; ) {
            if (groups[i].equals("0")) {
                int j = i;
                while (j < groups.length && groups[j].equals("0")) {
                    j++;
                }
                if (j - i > bestLen && j - i > 1) {
                    bestStart = i;
                    bestLen = j - i;
                }
                i = j;
            } else {
                i++;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < groups.length; i++) {
            if (i == bestStart) {
                sb.append("::");
                i += bestLen - 1;
                continue;
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') {
                sb.append(':');
            }
            sb.append(groups[i].toLowerCase());
        }
        return sb.toString();
    }
}
