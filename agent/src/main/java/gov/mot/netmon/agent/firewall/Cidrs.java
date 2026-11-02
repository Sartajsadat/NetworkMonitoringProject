package gov.mot.netmon.agent.firewall;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Small CIDR helpers for literal addresses. */
final class Cidrs {

    private Cidrs() {
    }

    static boolean hostBitsZero(byte[] addr, int prefix) {
        BigInteger v = new BigInteger(1, addr);
        int bits = addr.length * 8;
        BigInteger hostMask = BigInteger.ONE.shiftLeft(bits - prefix).subtract(BigInteger.ONE);
        return v.and(hostMask).signum() == 0;
    }

    static String format(byte[] addr) throws UnknownHostException {
        String s = InetAddress.getByAddress(addr).getHostAddress();
        if (addr.length == 16) s = compress(s);
        return s;
    }

    /** Java prints 2001:db8:0:0:0:0:0:1; use the short form (2001:db8::1). */
    static String compress(String full) {
        String[] parts = full.split(":");
        int bestStart = -1, bestLen = 0;
        for (int i = 0; i < parts.length; ) {
            if (parts[i].equals("0")) {
                int j = i;
                while (j < parts.length && parts[j].equals("0")) j++;
                if (j - i > bestLen && j - i > 1) {
                    bestStart = i;
                    bestLen = j - i;
                }
                i = j;
            } else {
                i++;
            }
        }
        if (bestStart < 0) return full;
        String left = String.join(":", java.util.Arrays.copyOfRange(parts, 0, bestStart));
        String right = String.join(":", java.util.Arrays.copyOfRange(parts, bestStart + bestLen, parts.length));
        return left + "::" + right;
    }

    /** [first, last] of a CIDR or a single address. */
    static BigInteger[] range(String cidr) {
        String a = cidr.contains("/") ? cidr.substring(0, cidr.indexOf('/')) : cidr;
        try {
            byte[] addr = InetAddress.getByName(a).getAddress();
            int bits = addr.length * 8;
            int prefix = cidr.contains("/") ? Integer.parseInt(cidr.substring(cidr.indexOf('/') + 1)) : bits;
            BigInteger v = new BigInteger(1, addr);
            BigInteger host = BigInteger.ONE.shiftLeft(bits - prefix).subtract(BigInteger.ONE);
            BigInteger first = v.andNot(host);
            // Encode the family in the value so IPv4 and IPv6 never overlap.
            BigInteger fam = addr.length == 4 ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(200);
            return new BigInteger[] {first.add(fam), first.or(host).add(fam)};
        } catch (UnknownHostException | RuntimeException e) {
            return null;
        }
    }

    static boolean overlaps(String a, String b) {
        BigInteger[] x = range(a);
        BigInteger[] y = range(b);
        return x != null && y != null && x[0].compareTo(y[1]) <= 0 && y[0].compareTo(x[1]) <= 0;
    }

    /** Only literal addresses are accepted (no DNS): digits/dots or hex/colons. */
    static boolean literal(String s) {
        return s != null && s.matches("^[0-9a-fA-F:.]{2,45}(/\\d{1,3})?$") && (s.contains(".") || s.contains(":"));
    }
}
