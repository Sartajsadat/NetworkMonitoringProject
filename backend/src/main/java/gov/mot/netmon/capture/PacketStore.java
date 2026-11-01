package gov.mot.netmon.capture;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import gov.mot.netmon.capture.CaptureDtos.Conversation;
import gov.mot.netmon.capture.CaptureDtos.CountRow;
import gov.mot.netmon.capture.CaptureDtos.PacketRow;
import gov.mot.netmon.capture.CaptureDtos.TimeBucket;

/** Packet metadata in PostgreSQL (packet_summaries). All queries are parameterised. */
@Repository
public class PacketStore {

    private final JdbcTemplate jdbc;

    public PacketStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID captureId, int segmentSeq, long firstFrame, List<Tshark.SummaryRow> rows) {
        jdbc.batchUpdate("""
                INSERT INTO packet_summaries (capture_id, frame_no, segment_seq, segment_frame, captured_at, src, dst,
                  src_port, dst_port, protocol, length, tcp_flags, retransmission, tcp_reset, dns_query, info)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                rows, 1000, (ps, r) -> {
                    ps.setObject(1, captureId);
                    ps.setLong(2, firstFrame + r.frame() - 1);
                    ps.setInt(3, segmentSeq);
                    ps.setInt(4, r.frame());
                    ps.setTimestamp(5, Timestamp.from(r.time()));
                    ps.setString(6, r.src());
                    ps.setString(7, r.dst());
                    ps.setObject(8, r.srcPort());
                    ps.setObject(9, r.dstPort());
                    ps.setString(10, r.protocol());
                    ps.setInt(11, r.length());
                    ps.setString(12, r.tcpFlags());
                    ps.setBoolean(13, r.retransmission());
                    ps.setBoolean(14, r.reset());
                    ps.setString(15, r.dnsQuery());
                    ps.setString(16, r.info());
                });
    }

    /** Structured filters for the packet table. All optional. */
    public record Filter(String src, String dst, String host, String protocol, Integer port, Integer minLength,
            Integer maxLength, Instant from, Instant to, String text, Collection<Long> frames) {
    }

    private record Where(String sql, List<Object> args) {
    }

    private Where where(UUID captureId, Filter f) {
        StringBuilder sql = new StringBuilder(" WHERE capture_id = ?");
        List<Object> args = new ArrayList<>(List.of(captureId));
        if (notBlank(f.src())) { sql.append(" AND src = ?"); args.add(f.src().trim()); }
        if (notBlank(f.dst())) { sql.append(" AND dst = ?"); args.add(f.dst().trim()); }
        if (notBlank(f.host())) { sql.append(" AND (src = ? OR dst = ?)"); args.add(f.host().trim()); args.add(f.host().trim()); }
        if (notBlank(f.protocol())) { sql.append(" AND upper(protocol) = upper(?)"); args.add(f.protocol().trim()); }
        if (f.port() != null) { sql.append(" AND (src_port = ? OR dst_port = ?)"); args.add(f.port()); args.add(f.port()); }
        if (f.minLength() != null) { sql.append(" AND length >= ?"); args.add(f.minLength()); }
        if (f.maxLength() != null) { sql.append(" AND length <= ?"); args.add(f.maxLength()); }
        if (f.from() != null) { sql.append(" AND captured_at >= ?"); args.add(Timestamp.from(f.from())); }
        if (f.to() != null) { sql.append(" AND captured_at < ?"); args.add(Timestamp.from(f.to())); }
        if (notBlank(f.text())) { sql.append(" AND info ILIKE ?"); args.add("%" + escapeLike(f.text().trim()) + "%"); }
        if (f.frames() != null) {
            if (f.frames().isEmpty()) {
                sql.append(" AND FALSE");
            } else {
                sql.append(" AND frame_no = ANY (?)");
                args.add(f.frames().toArray(new Long[0]));
            }
        }
        return new Where(sql.toString(), args);
    }

    public long count(UUID captureId, Filter f) {
        Where w = where(captureId, f);
        Long n = jdbc.queryForObject("SELECT count(*) FROM packet_summaries" + w.sql(), Long.class, w.args().toArray());
        return n == null ? 0 : n;
    }

    public List<PacketRow> page(UUID captureId, Filter f, int page, int size, boolean descending) {
        Where w = where(captureId, f);
        List<Object> args = new ArrayList<>(w.args());
        args.add(size);
        args.add((long) page * size);
        return jdbc.query("SELECT frame_no, captured_at, src, dst, src_port, dst_port, protocol, length, tcp_flags, "
                + "retransmission, tcp_reset, dns_query, info FROM packet_summaries" + w.sql()
                + " ORDER BY frame_no " + (descending ? "DESC" : "ASC") + " LIMIT ? OFFSET ?",
                (rs, i) -> new PacketRow(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4),
                        (Integer) rs.getObject(5), (Integer) rs.getObject(6), rs.getString(7), rs.getInt(8), rs.getString(9),
                        rs.getBoolean(10), rs.getBoolean(11), rs.getString(12), rs.getString(13)),
                args.toArray());
    }

    public record Location(int segmentSeq, int segmentFrame) {
    }

    public Location locate(UUID captureId, long frameNo) {
        List<Location> l = jdbc.query("SELECT segment_seq, segment_frame FROM packet_summaries WHERE capture_id = ? AND frame_no = ?",
                (rs, i) -> new Location(rs.getInt(1), rs.getInt(2)), captureId, frameNo);
        return l.isEmpty() ? null : l.get(0);
    }

    public PacketRow one(UUID captureId, long frameNo) {
        List<PacketRow> rows = page(captureId, new Filter(null, null, null, null, null, null, null, null, null, null,
                List.of(frameNo)), 0, 1, false);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------- statistics ----------------

    public List<CountRow> protocols(UUID id) {
        return counts("SELECT coalesce(protocol, '?'), count(*), sum(length) FROM packet_summaries WHERE capture_id = ? "
                + "GROUP BY 1 ORDER BY 2 DESC LIMIT 25", id);
    }

    public List<CountRow> topAddresses(UUID id, String column) {
        String col = column.equals("dst") ? "dst" : "src"; // whitelist: never interpolate user input
        return counts("SELECT " + col + ", count(*), sum(length) FROM packet_summaries WHERE capture_id = ? AND " + col
                + " IS NOT NULL GROUP BY 1 ORDER BY 2 DESC LIMIT 15", id);
    }

    public List<CountRow> topPorts(UUID id) {
        return counts("""
                SELECT coalesce(protocol, '?') || ' ' || port, count(*), sum(length) FROM (
                  SELECT protocol, length, CASE WHEN dst_port IS NULL THEN NULL
                         WHEN src_port IS NOT NULL AND src_port < dst_port THEN src_port ELSE dst_port END AS port
                    FROM packet_summaries WHERE capture_id = ?) t
                 WHERE port IS NOT NULL GROUP BY 1 ORDER BY 2 DESC LIMIT 15""", id);
    }

    public List<CountRow> dnsQueries(UUID id) {
        return counts("SELECT dns_query, count(*), sum(length) FROM packet_summaries WHERE capture_id = ? AND dns_query IS NOT NULL "
                + "GROUP BY 1 ORDER BY 2 DESC LIMIT 20", id);
    }

    /** Address pairs regardless of direction (A↔B), like Wireshark's Conversations window. */
    public List<Conversation> conversations(UUID id) {
        return jdbc.query("""
                SELECT least(src, dst) a, greatest(src, dst) b, count(*), sum(length),
                       sum(CASE WHEN src = least(src, dst) THEN 1 ELSE 0 END),
                       sum(CASE WHEN src = least(src, dst) THEN 0 ELSE 1 END),
                       min(captured_at), max(captured_at), string_agg(DISTINCT protocol, ', ')
                  FROM packet_summaries WHERE capture_id = ? AND src IS NOT NULL AND dst IS NOT NULL
                 GROUP BY 1, 2 ORDER BY 4 DESC LIMIT 30""",
                (rs, i) -> new Conversation(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                        rs.getLong(6), rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant(), cut(rs.getString(9), 120)),
                id);
    }

    public List<TimeBucket> timeline(UUID id, Instant from, Instant to) {
        if (from == null || to == null) return List.of();
        long span = Math.max(1, to.getEpochSecond() - from.getEpochSecond());
        long bucket = Math.max(1, (long) Math.ceil(span / 120.0)); // at most ~120 points
        return jdbc.query("""
                SELECT to_timestamp(floor(extract(epoch FROM captured_at) / ?) * ?) AS t, count(*), sum(length)
                  FROM packet_summaries WHERE capture_id = ? GROUP BY 1 ORDER BY 1""",
                (rs, i) -> new TimeBucket(rs.getTimestamp(1).toInstant(), rs.getLong(2), rs.getLong(3), bucket),
                bucket, bucket, id);
    }

    public Map<String, Object> totals(UUID id) {
        return jdbc.queryForMap("""
                SELECT count(*) AS packets, coalesce(sum(length), 0) AS bytes, min(captured_at) AS first, max(captured_at) AS last,
                       count(*) FILTER (WHERE tcp_reset) AS resets, count(*) FILTER (WHERE retransmission) AS retransmissions,
                       count(*) FILTER (WHERE tcp_flags = '0x0002') AS syns
                  FROM packet_summaries WHERE capture_id = ?""", id);
    }

    public void deleteForCapture(UUID id) {
        jdbc.update("DELETE FROM packet_summaries WHERE capture_id = ?", id);
    }

    private List<CountRow> counts(String sql, UUID id) {
        return jdbc.query(sql, (rs, i) -> new CountRow(rs.getString(1), rs.getLong(2), rs.getLong(3)), id);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String cut(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
