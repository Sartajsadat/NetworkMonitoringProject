package gov.mot.netmon.flow;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.common.Cidr;
import gov.mot.netmon.common.IpAddresses;
import gov.mot.netmon.common.PageResponse;
import gov.mot.netmon.flow.FlowDtos.Bucket;
import gov.mot.netmon.flow.FlowDtos.Conversation;
import gov.mot.netmon.flow.FlowDtos.ExporterRow;
import gov.mot.netmon.flow.FlowDtos.Filter;
import gov.mot.netmon.flow.FlowDtos.FlowView;
import gov.mot.netmon.flow.FlowDtos.PortRow;
import gov.mot.netmon.flow.FlowDtos.ProtocolRow;
import gov.mot.netmon.flow.FlowDtos.Summary;
import gov.mot.netmon.flow.FlowDtos.Talker;

/** Top talkers, ports, protocols and conversations from stored flow records. */
@Service
public class FlowQueryService {

    static final Duration MAX_RANGE = Duration.ofDays(31);
    static final long[] BUCKETS = {60, 300, 900, 3600, 3 * 3600, 6 * 3600, 86400};
    static final int TOP = 15;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public FlowQueryService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private String where(Filter f, MapSqlParameterSource p, Instant[] range) {
        Instant to = f.to() == null ? clock.instant() : f.to();
        Instant from = f.from() == null ? to.minus(Duration.ofHours(1)) : f.from();
        if (!from.isBefore(to)) throw ApiException.badRequest("INVALID_RANGE", "'from' must be before 'to'");
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) throw ApiException.badRequest("RANGE_TOO_LARGE", "At most 31 days");
        range[0] = from;
        range[1] = to;
        List<String> w = new ArrayList<>();
        w.add("f.flow_end >= :from AND f.flow_end <= :to");
        p.addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to));
        if (f.exporterId() != null) {
            w.add("f.exporter_id = :exp");
            p.addValue("exp", f.exporterId());
        }
        if (f.ip() != null && !f.ip().isBlank()) {
            try {
                p.addValue("ip", IpAddresses.parse(f.ip()).canonical());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("INVALID_ADDRESS", e.getMessage());
            }
            w.add("(f.src_ip = :ip::inet OR f.dst_ip = :ip::inet)");
        }
        if (f.subnet() != null && !f.subnet().isBlank()) {
            try {
                p.addValue("net", Cidr.parse(f.subnet()).network());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("INVALID_CIDR", e.getMessage());
            }
            w.add("(f.src_ip <<= :net::inet OR f.dst_ip <<= :net::inet)");
        }
        if (f.port() != null) {
            if (f.port() < 0 || f.port() > 65535) throw ApiException.badRequest("INVALID_PORT", "Port must be 0 to 65535");
            w.add("(f.src_port = :port OR f.dst_port = :port)");
            p.addValue("port", f.port());
        }
        if (f.protocol() != null) {
            if (f.protocol() < 0 || f.protocol() > 255) throw ApiException.badRequest("INVALID_PROTOCOL", "Protocol must be 0 to 255");
            w.add("f.protocol = :proto");
            p.addValue("proto", f.protocol());
        }
        return " WHERE " + String.join(" AND ", w);
    }

    @Transactional(readOnly = true)
    public Summary summary(Filter f) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("top", TOP);
        Instant[] range = new Instant[2];
        String where = where(f, p, range);
        String from = " FROM network_flows f" + where;
        long[] totals = new long[3];
        boolean[] sampled = new boolean[1];
        jdbc.query("SELECT count(*) AS n, COALESCE(sum(bytes), 0) AS b, COALESCE(sum(packets), 0) AS pk, "
                + "COALESCE(bool_or(sampling > 1), false) AS s" + from, p, rs -> {
                    totals[0] = rs.getLong("n");
                    totals[1] = rs.getLong("b");
                    totals[2] = rs.getLong("pk");
                    sampled[0] = rs.getBoolean("s");
                });
        List<Talker> sources = jdbc.query("SELECT host(f.src_ip) AS a, sum(bytes) AS b, sum(packets) AS pk, count(*) AS n" + from
                + " GROUP BY f.src_ip ORDER BY b DESC LIMIT :top", p,
                (rs, i) -> new Talker(rs.getString("a"), rs.getLong("b"), rs.getLong("pk"), rs.getLong("n")));
        List<Talker> dests = jdbc.query("SELECT host(f.dst_ip) AS a, sum(bytes) AS b, sum(packets) AS pk, count(*) AS n" + from
                + " GROUP BY f.dst_ip ORDER BY b DESC LIMIT :top", p,
                (rs, i) -> new Talker(rs.getString("a"), rs.getLong("b"), rs.getLong("pk"), rs.getLong("n")));
        // service port: the lower of the two ports is usually the server side
        List<PortRow> ports = jdbc.query("""
                SELECT f.protocol, CASE WHEN f.src_port IS NULL THEN NULL ELSE LEAST(f.src_port, f.dst_port) END AS port,
                       sum(bytes) AS b, sum(packets) AS pk, count(*) AS n""" + from + " GROUP BY 1, 2 ORDER BY b DESC LIMIT :top", p,
                (rs, i) -> new PortRow(rs.getInt("protocol"), (Integer) rs.getObject("port"), rs.getLong("b"), rs.getLong("pk"),
                        rs.getLong("n")));
        List<ProtocolRow> protocols = jdbc.query("SELECT f.protocol, sum(bytes) AS b, sum(packets) AS pk, count(*) AS n" + from
                + " GROUP BY 1 ORDER BY b DESC", p,
                (rs, i) -> new ProtocolRow(rs.getInt("protocol"), rs.getLong("b"), rs.getLong("pk"), rs.getLong("n")));
        List<Conversation> convs = jdbc.query("""
                SELECT host(LEAST(f.src_ip, f.dst_ip)) AS a, host(GREATEST(f.src_ip, f.dst_ip)) AS b, sum(bytes) AS by,
                       sum(packets) AS pk, count(*) AS n,
                       COALESCE(sum(bytes) FILTER (WHERE f.src_ip < f.dst_ip), 0) AS ab,
                       COALESCE(sum(bytes) FILTER (WHERE f.src_ip > f.dst_ip), 0) AS ba""" + from
                + " GROUP BY LEAST(f.src_ip, f.dst_ip), GREATEST(f.src_ip, f.dst_ip) ORDER BY by DESC LIMIT :top", p,
                (rs, i) -> new Conversation(rs.getString("a"), rs.getString("b"), rs.getLong("by"), rs.getLong("pk"), rs.getLong("n"),
                        rs.getLong("ab"), rs.getLong("ba")));
        long span = Duration.between(range[0], range[1]).toSeconds();
        long bucket = BUCKETS[BUCKETS.length - 1];
        for (long bs : BUCKETS) {
            if (span / bs <= 90) {
                bucket = bs;
                break;
            }
        }
        p.addValue("bucket", bucket);
        java.util.Map<Long, long[]> byBucket = new java.util.HashMap<>();
        jdbc.query("SELECT floor(extract(epoch FROM f.flow_end) / :bucket)::bigint AS k, sum(bytes) AS b, sum(packets) AS pk"
                + from + " GROUP BY 1", p, rs -> {
                    byBucket.put(rs.getLong("k"), new long[] {rs.getLong("b"), rs.getLong("pk")});
                });
        List<Bucket> timeline = new ArrayList<>();
        for (long k = range[0].getEpochSecond() / bucket; k <= range[1].getEpochSecond() / bucket; k++) {
            long[] v = byBucket.getOrDefault(k, new long[2]);
            timeline.add(new Bucket(Instant.ofEpochSecond(k * bucket), v[0], v[1]));
        }
        List<ExporterRow> exporters = jdbc.query("SELECT d.id, d.name, count(*) AS n, sum(f.bytes) AS b" + from.replace(
                " FROM network_flows f", " FROM network_flows f JOIN network_devices d ON d.id = f.exporter_id")
                + " GROUP BY d.id, d.name ORDER BY b DESC", p,
                (rs, i) -> new ExporterRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("n"), rs.getLong("b")));
        return new Summary(range[0], range[1], totals[0], totals[1], totals[2], sampled[0], bucket, sources, dests, ports,
                protocols, convs, timeline, exporters);
    }

    @Transactional(readOnly = true)
    public PageResponse<FlowView> records(Filter f, int page, int size) {
        if (page < 0 || size < 1 || size > 500) throw ApiException.badRequest("INVALID_PAGE", "Invalid page");
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = where(f, p, new Instant[2]);
        Long total = jdbc.queryForObject("SELECT count(*) FROM network_flows f" + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<FlowView> rows = jdbc.query("""
                SELECT f.*, host(f.src_ip) AS s, host(f.dst_ip) AS d, dev.name AS exporter_name
                FROM network_flows f LEFT JOIN network_devices dev ON dev.id = f.exporter_id""" + where
                + " ORDER BY f.flow_end DESC, f.id DESC LIMIT :limit OFFSET :offset", p,
                (rs, i) -> new FlowView(rs.getLong("id"), rs.getString("exporter_name"), rs.getTimestamp("flow_start").toInstant(),
                        rs.getTimestamp("flow_end").toInstant(), rs.getString("s"), (Integer) rs.getObject("src_port"), rs.getString("d"),
                        (Integer) rs.getObject("dst_port"), rs.getInt("protocol"), rs.getLong("bytes"), rs.getLong("packets"),
                        (Integer) rs.getObject("tcp_flags"), rs.getInt("sampling"), rs.getInt("version")));
        long t = total == null ? 0 : total;
        return new PageResponse<>(rows, page, size, t, (int) ((t + size - 1) / size));
    }
}
