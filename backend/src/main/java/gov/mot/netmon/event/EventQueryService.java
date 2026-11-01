package gov.mot.netmon.event;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.common.PageResponse;
import gov.mot.netmon.event.EventDtos.AlertRef;
import gov.mot.netmon.event.EventDtos.Bucket;
import gov.mot.netmon.event.EventDtos.CaptureRef;
import gov.mot.netmon.event.EventDtos.EventContext;
import gov.mot.netmon.event.EventDtos.EventFilter;
import gov.mot.netmon.event.EventDtos.EventView;
import gov.mot.netmon.event.EventDtos.Histogram;
import gov.mot.netmon.security.CurrentUser;

/** Timeline queries. Every query hides the categories the current user may not see. */
@Service
public class EventQueryService {

    static final Duration DEFAULT_RANGE = Duration.ofHours(24);
    static final Duration MAX_RANGE = Duration.ofDays(400);
    static final Duration RELATED_WINDOW = Duration.ofMinutes(15);
    static final Duration CONTEXT_WINDOW = Duration.ofMinutes(30);
    static final long[] BUCKETS = {60, 300, 900, 3600, 3 * 3600, 6 * 3600, 86400, 7 * 86400};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TelemetryCoverage coverage;
    private final Clock clock;

    public EventQueryService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, TelemetryCoverage coverage, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.coverage = coverage;
        this.clock = clock;
    }

    private static final String SELECT = """
            SELECT e.*, a.status AS alert_status FROM events e LEFT JOIN alerts a ON a.id = e.alert_id""";

    /** Builds the WHERE clause shared by search and histogram. */
    private String where(EventFilter f, MapSqlParameterSource p, Instant[] range) {
        Instant to = f.to() == null ? clock.instant() : f.to();
        Instant from = f.from() == null ? to.minus(DEFAULT_RANGE) : f.from();
        if (!from.isBefore(to)) throw ApiException.badRequest("INVALID_RANGE", "'from' must be before 'to'");
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) throw ApiException.badRequest("RANGE_TOO_LARGE", "Range is too large");
        range[0] = from;
        range[1] = to;
        List<String> w = new ArrayList<>();
        w.add("e.occurred_at >= :from AND e.occurred_at <= :to");
        p.addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to));
        Set<EventCategory> hidden = EventAccess.hiddenCategories();
        if (!hidden.isEmpty()) {
            w.add("e.category NOT IN (:hidden)");
            p.addValue("hidden", hidden.stream().map(Enum::name).toList());
        }
        if (f.serverId() != null) {
            w.add("e.server_id = :serverId");
            p.addValue("serverId", f.serverId());
        }
        if (f.ip() != null && !f.ip().isBlank()) {
            w.add("(e.ip_address = :ip OR e.server_id IN (SELECT server_id FROM server_ip_addresses WHERE ip_address = :ip))");
            p.addValue("ip", f.ip().trim());
        }
        if (f.categories() != null && !f.categories().isEmpty()) {
            w.add("e.category IN (:cats)");
            p.addValue("cats", f.categories().stream().map(Enum::name).toList());
        }
        if (f.minSeverity() != null) {
            List<String> sev = new ArrayList<>();
            for (EventSeverity s : EventSeverity.values()) if (s.atLeast(f.minSeverity())) sev.add(s.name());
            w.add("e.severity IN (:sev)");
            p.addValue("sev", sev);
        }
        if (f.service() != null && !f.service().isBlank()) {
            w.add("e.category = 'SERVICE' AND e.resource = :service");
            p.addValue("service", f.service().trim());
        }
        if (f.alertStatus() != null && !f.alertStatus().isBlank()) {
            switch (f.alertStatus()) {
                case "ANY" -> w.add("e.alert_id IS NOT NULL");
                case "ACTIVE" -> w.add("a.status IN ('OPEN', 'ACKNOWLEDGED')");
                case "OPEN", "ACKNOWLEDGED", "RESOLVED" -> {
                    w.add("a.status = :astatus");
                    p.addValue("astatus", f.alertStatus());
                }
                default -> throw ApiException.badRequest("INVALID_FILTER", "Unknown alert status");
            }
        }
        if (f.text() != null && !f.text().isBlank()) {
            w.add("(e.summary ILIKE :text OR e.resource ILIKE :text OR e.server_name ILIKE :text)");
            p.addValue("text", "%" + f.text().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (f.correlationId() != null) {
            w.add("e.correlation_id = :corr");
            p.addValue("corr", f.correlationId());
        }
        if (f.type() != null && !f.type().isBlank()) {
            w.add("e.event_type = :type");
            p.addValue("type", f.type().trim());
        }
        return " WHERE " + String.join(" AND ", w);
    }

    public PageResponse<EventView> search(EventFilter f, int page, int size, boolean ascending) {
        if (page < 0 || size < 1 || size > 500) throw ApiException.badRequest("INVALID_PAGE", "Invalid page");
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = where(f, p, new Instant[2]);
        Long total = jdbc.queryForObject("SELECT count(*) FROM events e LEFT JOIN alerts a ON a.id = e.alert_id" + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<EventView> rows = jdbc.query(SELECT + where + " ORDER BY e.occurred_at " + (ascending ? "ASC" : "DESC")
                + ", e.id " + (ascending ? "ASC" : "DESC") + " LIMIT :limit OFFSET :offset", p, this::map);
        long t = total == null ? 0 : total;
        return new PageResponse<>(rows, page, size, t, (int) ((t + size - 1) / size));
    }

    public Histogram histogram(EventFilter f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        Instant[] range = new Instant[2];
        String where = where(f, p, range);
        long span = Duration.between(range[0], range[1]).toSeconds();
        long bucket = BUCKETS[BUCKETS.length - 1];
        for (long b : BUCKETS) {
            if (span / b <= 72) {
                bucket = b;
                break;
            }
        }
        p.addValue("bucket", bucket);
        Map<Long, long[]> counts = new LinkedHashMap<>();
        Map<String, Long> bySev = new LinkedHashMap<>();
        Map<String, Long> byCat = new LinkedHashMap<>();
        long[] total = {0};
        jdbc.query("SELECT floor(extract(epoch FROM e.occurred_at) / :bucket)::bigint AS b, e.severity, e.category, count(*) AS n "
                + "FROM events e LEFT JOIN alerts a ON a.id = e.alert_id" + where + " GROUP BY 1, 2, 3", p, rs -> {
                    long b = rs.getLong("b");
                    String sev = rs.getString("severity");
                    long n = rs.getLong("n");
                    long[] c = counts.computeIfAbsent(b, k -> new long[4]);
                    c[EventSeverity.valueOf(sev).ordinal()] += n;
                    bySev.merge(sev, n, Long::sum);
                    byCat.merge(rs.getString("category"), n, Long::sum);
                    total[0] += n;
                });
        List<Bucket> buckets = new ArrayList<>();
        long first = range[0].getEpochSecond() / bucket;
        long last = range[1].getEpochSecond() / bucket;
        for (long b = first; b <= last; b++) {
            long[] c = counts.getOrDefault(b, new long[4]);
            buckets.add(new Bucket(Instant.ofEpochSecond(b * bucket), c[0], c[1], c[2], c[3]));
        }
        return new Histogram(range[0], range[1], bucket, buckets, bySev, byCat, total[0]);
    }

    public EventView get(long id) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        List<EventView> rows = jdbc.query(SELECT + " WHERE e.id = :id", p, this::map);
        if (rows.isEmpty() || EventAccess.hiddenCategories().contains(rows.get(0).category())) {
            throw ApiException.notFound("Event");
        }
        return rows.get(0);
    }

    public EventContext context(long id) {
        EventView e = get(id);
        Set<EventCategory> hidden = EventAccess.hiddenCategories();
        Instant t = e.occurredAt();
        Instant now = clock.instant();
        Instant from = t.minus(CONTEXT_WINDOW);
        Instant to = t.plus(CONTEXT_WINDOW).isAfter(now) ? now : t.plus(CONTEXT_WINDOW);
        MapSqlParameterSource p = new MapSqlParameterSource("id", id).addValue("corr", e.correlationId())
                .addValue("server", e.serverId()).addValue("rf", Timestamp.from(t.minus(RELATED_WINDOW)))
                .addValue("rt", Timestamp.from(t.plus(RELATED_WINDOW)));
        List<String> or = new ArrayList<>();
        if (e.correlationId() != null) or.add("e.correlation_id = :corr");
        if (e.serverId() != null) or.add("(e.server_id = :server AND e.occurred_at BETWEEN :rf AND :rt)");
        List<EventView> related = new ArrayList<>();
        if (!or.isEmpty()) {
            String hide = "";
            if (!hidden.isEmpty()) {
                hide = " AND e.category NOT IN (:hidden)";
                p.addValue("hidden", hidden.stream().map(Enum::name).toList());
            }
            related = jdbc.query(SELECT + " WHERE e.id <> :id AND (" + String.join(" OR ", or) + ")" + hide
                    + " ORDER BY e.occurred_at, e.id LIMIT 100", p, this::map);
        }
        EventDtos.Coverage cov = e.serverId() == null ? null : coverage.coverage(e.serverId(), from, to);
        boolean capturesHidden = !EventAccess.canSeeCaptures(CurrentUser.require());
        List<CaptureRef> captures = List.of();
        if (e.serverId() != null && !capturesHidden) {
            captures = jdbc.query("""
                    SELECT id, name, status, started_at, ended_at, files_deleted_at FROM packet_captures
                    WHERE server_id = :server AND COALESCE(started_at, requested_at) <= :to AND COALESCE(ended_at, now()) >= :from
                    ORDER BY requested_at""",
                    new MapSqlParameterSource("server", e.serverId()).addValue("from", Timestamp.from(from))
                            .addValue("to", Timestamp.from(to)),
                    (rs, i) -> new CaptureRef(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("status"),
                            ts(rs, "started_at"), ts(rs, "ended_at"), rs.getTimestamp("files_deleted_at") == null));
        }
        AlertRef alert = null;
        if (e.alertId() != null) {
            alert = jdbc.query("SELECT id, rule_name, status, severity FROM alerts WHERE id = :id",
                    new MapSqlParameterSource("id", e.alertId()),
                    rs -> rs.next() ? new AlertRef(rs.getObject("id", UUID.class), rs.getString("rule_name"), rs.getString("status"),
                            rs.getString("severity")) : null);
        }
        return new EventContext(e, related, from, to, cov, captures, capturesHidden, alert);
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    EventView map(ResultSet rs, int i) throws SQLException {
        JsonNode details = null;
        String json = rs.getString("details");
        if (json != null) {
            try {
                details = mapper.readTree(json);
            } catch (Exception ex) {
                details = null;
            }
        }
        return new EventView(rs.getLong("id"), ts(rs, "occurred_at"), ts(rs, "received_at"),
                EventCategory.valueOf(rs.getString("category")), rs.getString("event_type"),
                EventSeverity.valueOf(rs.getString("severity")), rs.getString("source"), rs.getObject("server_id", UUID.class),
                rs.getString("server_name"), rs.getString("resource"), rs.getString("ip_address"), rs.getString("summary"),
                details, rs.getObject("correlation_id", UUID.class), rs.getObject("alert_id", UUID.class),
                rs.getString("alert_status"), rs.getObject("capture_id", UUID.class));
    }
}
