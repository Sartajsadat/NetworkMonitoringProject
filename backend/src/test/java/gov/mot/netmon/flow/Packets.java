package gov.mot.netmon.flow;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/** Builds NetFlow/IPFIX packets byte by byte for tests. */
final class Packets {

    private Packets() {
    }

    static byte[] ip(String s) {
        try {
            return InetAddress.getByName(s).getAddress();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** NetFlow v5 with one TCP flow src:sport -> dst:dport. */
    static byte[] v5(long unixSecs, String src, int sport, String dst, int dport, long bytes, long packets, int samplingField) {
        ByteBuffer b = ByteBuffer.allocate(24 + 48);
        b.putShort((short) 5).putShort((short) 1).putInt(100_000).putInt((int) unixSecs).putInt(0).putInt(1)
                .put((byte) 0).put((byte) 0).putShort((short) samplingField);
        b.put(ip(src)).put(ip(dst)).put(ip("0.0.0.0")).putShort((short) 1).putShort((short) 2).putInt((int) packets)
                .putInt((int) bytes).putInt(40_000).putInt(90_000).putShort((short) sport).putShort((short) dport)
                .put((byte) 0).put((byte) 0x1b).put((byte) 6).put((byte) 0).putShort((short) 0).putShort((short) 0)
                .put((byte) 24).put((byte) 24).putShort((short) 0);
        return b.array();
    }

    private static byte[] set(int id, byte[] body) {
        ByteBuffer b = ByteBuffer.allocate(4 + body.length);
        b.putShort((short) id).putShort((short) (4 + body.length)).put(body);
        return b.array();
    }

    /** NetFlow v9: one template (id 256) and one data record using it; withTemplate=false sends only the data. */
    static byte[] v9(boolean withTemplate, long unixSecs, String src, String dst, int sport, int dport, long bytes) {
        ByteArrayOutputStream sets = new ByteArrayOutputStream();
        int[][] fields = {{8, 4}, {12, 4}, {7, 2}, {11, 2}, {4, 1}, {1, 4}, {2, 4}, {22, 4}, {21, 4}, {6, 1}};
        if (withTemplate) {
            ByteBuffer t = ByteBuffer.allocate(4 + fields.length * 4);
            t.putShort((short) 256).putShort((short) fields.length);
            for (int[] f : fields) t.putShort((short) f[0]).putShort((short) f[1]);
            sets.writeBytes(set(0, t.array()));
        }
        ByteBuffer d = ByteBuffer.allocate(4 + 4 + 2 + 2 + 1 + 4 + 4 + 4 + 4 + 1 + 2); // + 2 padding
        d.put(ip(src)).put(ip(dst)).putShort((short) sport).putShort((short) dport).put((byte) 17).putInt((int) bytes).putInt(3)
                .putInt(10_000).putInt(20_000).put((byte) 0).putShort((short) 0);
        sets.writeBytes(set(256, d.array()));
        byte[] body = sets.toByteArray();
        ByteBuffer h = ByteBuffer.allocate(20 + body.length);
        h.putShort((short) 9).putShort((short) 2).putInt(30_000).putInt((int) unixSecs).putInt(7).putInt(1).put(body);
        return h.array();
    }

    /** IPFIX: an options template announcing 1:100 sampling, a template with IPv6 and a variable-length field, and data. */
    static byte[] ipfix(long exportSecs, long startMs, long endMs) {
        ByteArrayOutputStream sets = new ByteArrayOutputStream();
        // options template 300: scope observationDomainId(149,4), samplingPacketInterval(305,4)
        ByteBuffer ot = ByteBuffer.allocate(6 + 8);
        ot.putShort((short) 300).putShort((short) 2).putShort((short) 1).putShort((short) 149).putShort((short) 4)
                .putShort((short) 305).putShort((short) 4);
        sets.writeBytes(set(3, ot.array()));
        sets.writeBytes(set(300, ByteBuffer.allocate(8).putInt(1).putInt(100).array()));
        // template 400: src v6, dst v6, ports, protocol, octetDeltaCount(8), packetDeltaCount(8), start/end ms, a vendor field
        // with enterprise bit and variable length, and interfaceName(82, variable)
        ByteBuffer t = ByteBuffer.allocate(4 + 11 * 4 + 4);
        t.putShort((short) 400).putShort((short) 11)
                .putShort((short) 27).putShort((short) 16).putShort((short) 28).putShort((short) 16)
                .putShort((short) 7).putShort((short) 2).putShort((short) 11).putShort((short) 2)
                .putShort((short) 4).putShort((short) 1).putShort((short) 1).putShort((short) 8)
                .putShort((short) 2).putShort((short) 8).putShort((short) 152).putShort((short) 8)
                .putShort((short) 153).putShort((short) 8)
                .putShort((short) (0x8000 | 12)).putShort((short) 65535).putInt(9)   // enterprise 9 (vendor), skipped
                .putShort((short) 82).putShort((short) 65535);
        sets.writeBytes(set(2, t.array()));
        ByteBuffer d = ByteBuffer.allocate(16 + 16 + 2 + 2 + 1 + 8 + 8 + 8 + 8 + 1 + 3 + 1 + 4);
        d.put(ip("2001:db8::10")).put(ip("2001:db8::20")).putShort((short) 50000).putShort((short) 443).put((byte) 6)
                .putLong(123_456).putLong(99).putLong(startMs).putLong(endMs)
                .put((byte) 3).put(new byte[] {1, 2, 3}).put((byte) 4).put("eth0".getBytes());
        sets.writeBytes(set(400, d.array()));
        byte[] body = sets.toByteArray();
        ByteBuffer h = ByteBuffer.allocate(16 + body.length);
        h.putShort((short) 10).putShort((short) (16 + body.length)).putInt((int) exportSecs).putInt(1).putInt(1).put(body);
        return h.array();
    }
}
