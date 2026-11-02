package gov.mot.netmon.common;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * An IPv4 or IPv6 network in CIDR notation. Parsing is strict: the address must be a literal
 * (validated by {@link IpAddresses}, so no DNS lookup can happen) and its host bits must be zero
 * ("10.0.0.5/24" is rejected with a hint to use "10.0.0.0/24").
 */
public record Cidr(String network, int version, int prefix, BigInteger first, BigInteger last) {

    public static Cidr parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("CIDR is required");
        String v = raw.trim();
        int slash = v.indexOf('/');
        if (slash < 0) throw new IllegalArgumentException("Use CIDR notation, e.g. 10.0.0.0/24");
        IpAddresses.ParsedIp ip = IpAddresses.parse(v.substring(0, slash));
        int bits = ip.version() == 4 ? 32 : 128;
        int prefix;
        try {
            prefix = Integer.parseInt(v.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid prefix length");
        }
        if (prefix < 0 || prefix > bits) throw new IllegalArgumentException("Prefix must be 0 to " + bits);
        BigInteger value = toBig(ip.canonical());
        BigInteger hostMask = BigInteger.ONE.shiftLeft(bits - prefix).subtract(BigInteger.ONE);
        BigInteger network = value.andNot(hostMask);
        if (!network.equals(value)) {
            throw new IllegalArgumentException("Host bits are set; the network is " + toText(network, ip.version()) + "/" + prefix);
        }
        return new Cidr(toText(network, ip.version()) + "/" + prefix, ip.version(), prefix, network, network.or(hostMask));
    }

    public boolean contains(String address) {
        IpAddresses.ParsedIp ip = IpAddresses.parse(address);
        if (ip.version() != version) return false;
        BigInteger v = toBig(ip.canonical());
        return v.compareTo(first) >= 0 && v.compareTo(last) <= 0;
    }

    /** Number of addresses in the network. */
    public BigInteger size() {
        return last.subtract(first).add(BigInteger.ONE);
    }

    /** Addresses hosts can use (IPv4: minus network and broadcast, except /31 and /32). */
    public BigInteger usable() {
        if (version == 4 && prefix <= 30) return size().subtract(BigInteger.TWO);
        return size();
    }

    public static BigInteger toBig(String literal) {
        try {
            return new BigInteger(1, InetAddress.getByName(IpAddresses.parse(literal).canonical()).getAddress());
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid address");
        }
    }

    static String toText(BigInteger v, int version) {
        int len = version == 4 ? 4 : 16;
        byte[] raw = v.toByteArray();
        byte[] out = new byte[len];
        int copy = Math.min(raw.length, len);
        System.arraycopy(raw, raw.length - copy, out, len - copy, copy);
        try {
            return IpAddresses.parse(InetAddress.getByAddress(out).getHostAddress()).canonical();
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
