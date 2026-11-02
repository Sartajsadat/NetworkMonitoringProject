package gov.mot.netmon.flow;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decodes NetFlow v5, NetFlow v9 (RFC 3954) and IPFIX (RFC 7011) packets. v9 and IPFIX are
 * template based: templates are remembered per exporter, observation domain and template id, and
 * data sets whose template has not arrived yet are counted as skipped (exporters resend templates).
 * Every length is checked against the packet before it is read; a malformed packet is rejected.
 */
public class NetflowParser {

    /** Information element numbers (shared by v9 and IPFIX). */
    static final int OCTETS = 1, PACKETS = 2, PROTOCOL = 4, TCP_FLAGS = 6, SRC_PORT = 7, SRC_V4 = 8, INPUT_IF = 10,
            DST_PORT = 11, DST_V4 = 12, OUTPUT_IF = 14, LAST_UPTIME = 21, FIRST_UPTIME = 22, SRC_V6 = 27, DST_V6 = 28,
            SAMPLING_INTERVAL = 34, OCTETS_TOTAL = 85, PACKETS_TOTAL = 86, START_SEC = 150, END_SEC = 151, START_MS = 152,
            END_MS = 153, SYS_INIT_MS = 160, SAMPLING_PACKET_INTERVAL = 305;

    public static class ParseException extends Exception {
        public ParseException(String m) {
            super(m);
        }
    }

    record Field(int type, int length, boolean enterprise) {
    }

    record Template(List<Field> fields, int scopeCount) {
    }

    public record Result(int version, List<FlowRecord> flows, int skippedSets, Integer sampling) {
    }

    private final Map<String, Template> templates = new ConcurrentHashMap<>();
    /** Sampling interval announced in v9/IPFIX options data, per exporter and domain. */
    private final Map<String, Integer> samplingByDomain = new ConcurrentHashMap<>();

    public Result parse(String exporter, byte[] data, int length, Instant receivedAt) throws ParseException {
        if (length < 4) throw new ParseException("packet too short");
        ByteBuffer b = ByteBuffer.wrap(data, 0, length);
        int version = b.getShort(0) & 0xffff;
        return switch (version) {
            case 5 -> v5(b, length);
            case 9 -> v9(exporter, b, length);
            case 10 -> ipfix(exporter, b, length);
            default -> throw new ParseException("unsupported version " + version);
        };
    }

    // ------------------------------------------------------------------ v5

    private Result v5(ByteBuffer b, int length) throws ParseException {
        if (length < 24) throw new ParseException("v5 header too short");
        int count = b.getShort(2) & 0xffff;
        long uptime = b.getInt(4) & 0xffffffffL;
        long secs = b.getInt(8) & 0xffffffffL;
        long nsecs = b.getInt(12) & 0xffffffffL;
        int samplingField = b.getShort(22) & 0xffff;
        int sampling = Math.max(1, samplingField & 0x3fff);
        if (count > 30 || length < 24 + count * 48) throw new ParseException("v5 record count does not match the packet");
        long exportMs = secs * 1000 + nsecs / 1_000_000;
        List<FlowRecord> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int o = 24 + i * 48;
            InetAddress src = addr(b, o, 4);
            InetAddress dst = addr(b, o + 4, 4);
            int in = b.getShort(o + 12) & 0xffff;
            int outIf = b.getShort(o + 14) & 0xffff;
            long pkts = b.getInt(o + 16) & 0xffffffffL;
            long octets = b.getInt(o + 20) & 0xffffffffL;
            long first = b.getInt(o + 24) & 0xffffffffL;
            long last = b.getInt(o + 28) & 0xffffffffL;
            int sport = b.getShort(o + 32) & 0xffff;
            int dport = b.getShort(o + 34) & 0xffff;
            int flags = b.get(o + 37) & 0xff;
            int proto = b.get(o + 38) & 0xff;
            Instant start = Instant.ofEpochMilli(exportMs - (uptime - first));
            Instant end = Instant.ofEpochMilli(exportMs - (uptime - last));
            out.add(new FlowRecord(start, end, src, dst, ports(proto) ? sport : null, ports(proto) ? dport : null, proto, octets,
                    pkts, proto == 6 ? flags : null, in, outIf, sampling, 5));
        }
        return new Result(5, out, 0, samplingField == 0 ? null : sampling);
    }

    // ------------------------------------------------------------------ v9

    private Result v9(String exporter, ByteBuffer b, int length) throws ParseException {
        if (length < 20) throw new ParseException("v9 header too short");
        long uptime = b.getInt(4) & 0xffffffffL;
        long secs = b.getInt(8) & 0xffffffffL;
        long sourceId = b.getInt(16) & 0xffffffffL;
        String domain = exporter + "/9/" + sourceId;
        long exportMs = secs * 1000;
        List<FlowRecord> out = new ArrayList<>();
        int skipped = 0;
        int p = 20;
        while (p + 4 <= length) {
            int setId = b.getShort(p) & 0xffff;
            int setLen = b.getShort(p + 2) & 0xffff;
            if (setLen < 4 || p + setLen > length) throw new ParseException("v9 flowset length out of range");
            int end = p + setLen;
            if (setId == 0) {
                int q = p + 4;
                while (q + 4 <= end) {
                    int id = b.getShort(q) & 0xffff;
                    int n = b.getShort(q + 2) & 0xffff;
                    q += 4;
                    if (q + n * 4 > end) throw new ParseException("v9 template too long");
                    List<Field> fields = new ArrayList<>(n);
                    for (int i = 0; i < n; i++, q += 4) fields.add(new Field(b.getShort(q) & 0xffff, b.getShort(q + 2) & 0xffff, false));
                    templates.put(domain + "/" + id, new Template(fields, 0));
                }
            } else if (setId == 1) {
                int q = p + 4;
                while (q + 6 <= end) {
                    int id = b.getShort(q) & 0xffff;
                    int scopeLen = b.getShort(q + 2) & 0xffff;
                    int optLen = b.getShort(q + 4) & 0xffff;
                    q += 6;
                    if (q + scopeLen + optLen > end || scopeLen % 4 != 0 || optLen % 4 != 0) break;
                    List<Field> fields = new ArrayList<>();
                    for (int i = 0; i < (scopeLen + optLen) / 4; i++, q += 4) {
                        fields.add(new Field(b.getShort(q) & 0xffff, b.getShort(q + 2) & 0xffff, false));
                    }
                    templates.put(domain + "/" + id, new Template(fields, scopeLen / 4));
                    // padding to 4 bytes may follow
                    if (end - q < 6) break;
                }
            } else if (setId >= 256) {
                Template t = templates.get(domain + "/" + setId);
                if (t == null) {
                    skipped++;
                } else {
                    decodeRecords(b, p + 4, end, t, domain, 9, uptime, exportMs, out);
                }
            }
            p = end;
        }
        return new Result(9, out, skipped, samplingByDomain.get(domain));
    }

    // ------------------------------------------------------------------ IPFIX

    private Result ipfix(String exporter, ByteBuffer b, int length) throws ParseException {
        if (length < 16) throw new ParseException("IPFIX header too short");
        int msgLen = b.getShort(2) & 0xffff;
        if (msgLen > length || msgLen < 16) throw new ParseException("IPFIX length does not match the packet");
        long exportSecs = b.getInt(4) & 0xffffffffL;
        long domainId = b.getInt(12) & 0xffffffffL;
        String domain = exporter + "/10/" + domainId;
        List<FlowRecord> out = new ArrayList<>();
        int skipped = 0;
        int p = 16;
        while (p + 4 <= msgLen) {
            int setId = b.getShort(p) & 0xffff;
            int setLen = b.getShort(p + 2) & 0xffff;
            if (setLen < 4 || p + setLen > msgLen) throw new ParseException("IPFIX set length out of range");
            int end = p + setLen;
            if (setId == 2 || setId == 3) {
                int q = p + 4;
                while (q + 4 <= end) {
                    int id = b.getShort(q) & 0xffff;
                    int n = b.getShort(q + 2) & 0xffff;
                    q += 4;
                    int scope = 0;
                    if (setId == 3) {
                        if (q + 2 > end) break;
                        scope = b.getShort(q) & 0xffff;
                        q += 2;
                    }
                    if (n == 0) { // template withdrawal
                        templates.remove(domain + "/" + id);
                        continue;
                    }
                    List<Field> fields = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        if (q + 4 > end) throw new ParseException("IPFIX template too long");
                        int type = b.getShort(q) & 0xffff;
                        int len = b.getShort(q + 2) & 0xffff;
                        q += 4;
                        boolean ent = (type & 0x8000) != 0;
                        if (ent) {
                            if (q + 4 > end) throw new ParseException("IPFIX template too long");
                            q += 4; // enterprise number: vendor field, skipped when decoding
                        }
                        fields.add(new Field(type & 0x7fff, len, ent));
                    }
                    templates.put(domain + "/" + id, new Template(fields, scope));
                }
            } else if (setId >= 256) {
                Template t = templates.get(domain + "/" + setId);
                if (t == null) {
                    skipped++;
                } else {
                    decodeRecords(b, p + 4, end, t, domain, 10, -1, exportSecs * 1000, out);
                }
            }
            p = end;
        }
        return new Result(10, out, skipped, samplingByDomain.get(domain));
    }

    // ------------------------------------------------------------------ records

    private void decodeRecords(ByteBuffer b, int start, int end, Template t, String domain, int version, long uptime, long exportMs,
            List<FlowRecord> out) throws ParseException {
        int q = start;
        int minLen = 0;
        for (Field f : t.fields()) minLen += f.length() == 65535 ? 1 : f.length();
        if (minLen == 0) return;
        while (q + minLen <= end) {
            long[] v = new long[400];
            boolean[] has = new boolean[400];
            InetAddress src = null;
            InetAddress dst = null;
            for (Field f : t.fields()) {
                int len = f.length();
                if (len == 65535) { // IPFIX variable length
                    if (q >= end) throw new ParseException("truncated record");
                    len = b.get(q) & 0xff;
                    q++;
                    if (len == 255) {
                        if (q + 2 > end) throw new ParseException("truncated record");
                        len = b.getShort(q) & 0xffff;
                        q += 2;
                    }
                }
                if (q + len > end) throw new ParseException("truncated record");
                if (!f.enterprise()) {
                    int type = f.type();
                    if ((type == SRC_V4 || type == DST_V4) && len == 4 || (type == SRC_V6 || type == DST_V6) && len == 16) {
                        InetAddress a = addr(b, q, len);
                        if (type == SRC_V4 || type == SRC_V6) src = a; else dst = a;
                    } else if (type < 400 && len <= 8 && len > 0) {
                        long x = 0;
                        for (int i = 0; i < len; i++) x = (x << 8) | (b.get(q + i) & 0xff);
                        v[type] = x;
                        has[type] = true;
                    }
                }
                q += len;
            }
            if (t.scopeCount() > 0) { // options record: only the sampling interval is of interest
                long s = has[SAMPLING_INTERVAL] ? v[SAMPLING_INTERVAL] : has[SAMPLING_PACKET_INTERVAL] ? v[SAMPLING_PACKET_INTERVAL] : 0;
                if (s > 0 && s < Integer.MAX_VALUE) samplingByDomain.put(domain, (int) s);
                continue;
            }
            if (src == null || dst == null) continue; // not an IP flow (e.g. layer-2 only)
            long octets = has[OCTETS] ? v[OCTETS] : v[OCTETS_TOTAL];
            long pkts = has[PACKETS] ? v[PACKETS] : v[PACKETS_TOTAL];
            Instant startT;
            Instant endT;
            if (has[START_MS] && has[END_MS]) {
                startT = Instant.ofEpochMilli(v[START_MS]);
                endT = Instant.ofEpochMilli(v[END_MS]);
            } else if (has[START_SEC] && has[END_SEC]) {
                startT = Instant.ofEpochSecond(v[START_SEC]);
                endT = Instant.ofEpochSecond(v[END_SEC]);
            } else if (has[FIRST_UPTIME] && has[LAST_UPTIME] && (uptime >= 0 || has[SYS_INIT_MS])) {
                long base = uptime >= 0 ? exportMs - uptime : v[SYS_INIT_MS];
                startT = Instant.ofEpochMilli(base + v[FIRST_UPTIME]);
                endT = Instant.ofEpochMilli(base + v[LAST_UPTIME]);
            } else {
                startT = Instant.ofEpochMilli(exportMs);
                endT = startT;
            }
            int proto = (int) v[PROTOCOL];
            Integer sampling = has[SAMPLING_INTERVAL] && v[SAMPLING_INTERVAL] > 0 ? Integer.valueOf((int) v[SAMPLING_INTERVAL])
                    : samplingByDomain.get(domain);
            out.add(new FlowRecord(startT, endT, src, dst, ports(proto) && has[SRC_PORT] ? (int) v[SRC_PORT] : null,
                    ports(proto) && has[DST_PORT] ? (int) v[DST_PORT] : null, proto, octets, pkts,
                    proto == 6 && has[TCP_FLAGS] ? (int) (v[TCP_FLAGS] & 0xff) : null,
                    has[INPUT_IF] ? (int) v[INPUT_IF] : null, has[OUTPUT_IF] ? (int) v[OUTPUT_IF] : null,
                    sampling == null ? 1 : sampling, version));
        }
    }

    private static boolean ports(int proto) {
        return proto == 6 || proto == 17 || proto == 132;
    }

    private static InetAddress addr(ByteBuffer b, int off, int len) {
        byte[] a = new byte[len];
        for (int i = 0; i < len; i++) a[i] = b.get(off + i);
        try {
            return InetAddress.getByAddress(a); // raw bytes: never a name lookup
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
