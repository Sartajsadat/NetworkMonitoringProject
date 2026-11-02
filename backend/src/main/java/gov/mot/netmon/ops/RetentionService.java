package gov.mot.netmon.ops;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import gov.mot.netmon.audit.AuditAction;
import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.capture.CaptureJobs;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.metrics.MetricRetentionJob;
import gov.mot.netmon.security.CurrentUser;

/**
 * Retention policies (one per kind of data), editable by super administrators and enforced every
 * hour. Each run records what it deleted; manual runs and policy changes are audited.
 *
 * <p>Until an administrator changes a policy it follows the environment default (METRICS_RAW_RETENTION,
 * EVENTS_RETENTION, ...), so existing deployments keep their settings.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    static final List<String> ENDED_RULES = List.of("REMOVED", "EXPIRED", "REJECTED", "CANCELLED");

    /** Where each kind of data lives: table, time column, extra condition. */
    record Store(String table, String column, String where) {
    }

    static final Map<String, Store> STORES = new LinkedHashMap<>();
    static {
        STORES.put("METRICS_RAW", new Store("metric_samples", "collected_at", null));
        STORES.put("METRICS_ROLLUP", new Store("metric_rollups_5m", "bucket_start", null));
        STORES.put("EVENTS", new Store("events", "occurred_at", null));
        STORES.put("ALERTS_RESOLVED", new Store("alerts", "resolved_at", "status = 'RESOLVED'"));
        STORES.put("FLOWS", new Store("network_flows", "flow_end", null));
        STORES.put("CAPTURES", new Store("packet_captures", "requested_at", "files_deleted_at IS NULL"));
        STORES.put("FIREWALL_HISTORY", new Store("firewall_rules", "updated_at", "status IN ('REMOVED','EXPIRED','REJECTED','CANCELLED')"));
        STORES.put("AUDIT_LOGS", new Store("audit_logs", "occurred_at", null));
    }

    public record Policy(String dataType, int retentionDays, int minDays, int maxDays, boolean fromEnvironment, String updatedBy,
            Instant updatedAt, Instant lastRunAt, Long lastDeleted, String lastError, Long rows, Long bytes, Instant oldest) {
    }

    public record RunResult(String dataType, long deleted, String error) {
    }

    private final JdbcTemplate jdbc;
    private final MetricRetentionJob metrics;
    private final CaptureJobs captures;
    private final AuditService audit;
    private final AppProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;

    public RetentionService(JdbcTemplate jdbc, MetricRetentionJob metrics, CaptureJobs captures, AuditService audit, AppProperties props,
            Clock clock, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.captures = captures;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
        this.tx = tx;
    }

    /** Environment defaults (days) for policies nobody has changed in the UI. */
    Map<String, Integer> environmentDefaults() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("METRICS_RAW", days(props.monitoring().rawRetention()));
        m.put("METRICS_ROLLUP", days(props.monitoring().rollupRetention()));
        m.put("EVENTS", days(props.events().retention()));
        m.put("ALERTS_RESOLVED", days(props.alerts().resolvedRetention()));
        m.put("FLOWS", days(props.flows().retention()));
        m.put("CAPTURES", props.capture().maxRetentionDays());
        return m;
    }

    static int days(Duration d) {
        return (int) Math.max(1, d.toDays());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncEnvironmentDefaults() {
        environmentDefaults().forEach((type, d) -> jdbc.update("""
                UPDATE retention_policies SET retention_days = GREATEST(min_days, LEAST(max_days, ?))
                 WHERE data_type = ? AND updated_by = 'system'""", d, type));
    }

    public int days(String type) {
        Integer d = jdbc.queryForObject("SELECT retention_days FROM retention_policies WHERE data_type = ?", Integer.class, type);
        if (d == null) throw new IllegalStateException("No retention policy " + type);
        return d;
    }

    /** Raw and rollup metrics are purged together (rollups are rebuilt first); the counts are split. */
    private long[] lastMetrics;

    public List<Policy> list() {
        return jdbc.query("SELECT * FROM retention_policies", (rs, i) -> {
            String type = rs.getString("data_type");
            Store st = STORES.get(type);
            Long rows = null;
            Long bytes = null;
            Instant oldest = null;
            if (st != null) {
                // Planner estimate for large tables; an exact count would scan millions of rows.
                rows = jdbc.queryForObject("SELECT GREATEST(reltuples, 0)::bigint FROM pg_class WHERE oid = ?::regclass", Long.class, st.table());
                bytes = jdbc.queryForObject("SELECT pg_total_relation_size(?::regclass)", Long.class, st.table());
                Timestamp ts = jdbc.queryForObject("SELECT min(" + st.column() + ") FROM " + st.table()
                        + (st.where() == null ? "" : " WHERE " + st.where()), Timestamp.class);
                oldest = ts == null ? null : ts.toInstant();
                if ("CAPTURES".equals(type)) {
                    bytes = jdbc.queryForObject("SELECT COALESCE(sum(bytes_stored), 0) FROM packet_captures WHERE files_deleted_at IS NULL",
                            Long.class);
                }
            }
            Timestamp lr = rs.getTimestamp("last_run_at");
            long ld = rs.getLong("last_deleted");
            return new Policy(type, rs.getInt("retention_days"), rs.getInt("min_days"), rs.getInt("max_days"),
                    "system".equals(rs.getString("updated_by")), rs.getString("updated_by"), rs.getTimestamp("updated_at").toInstant(),
                    lr == null ? null : lr.toInstant(), rs.wasNull() ? null : ld, rs.getString("last_error"), rows, bytes, oldest);
        }).stream().sorted((a, b) -> order(a.dataType()) - order(b.dataType())).toList();
    }

    static int order(String type) {
        return List.copyOf(STORES.keySet()).indexOf(type);
    }

    public Policy update(String type, int days, String reason) {
        var user = CurrentUser.require();
        Map<String, Object> row = jdbc.queryForList("SELECT * FROM retention_policies WHERE data_type = ?", type).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Retention policy"));
        int min = (Integer) row.get("min_days");
        int max = (Integer) row.get("max_days");
        if (days < min || days > max) {
            throw ApiException.badRequest("RETENTION_OUT_OF_RANGE", "Retention for " + type + " must be " + min + " to " + max + " days");
        }
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "Give a reason for the change");
        int before = (Integer) row.get("retention_days");
        jdbc.update("UPDATE retention_policies SET retention_days = ?, updated_by = ?, updated_at = ? WHERE data_type = ?", days,
                user.username(), Timestamp.from(clock.instant()), type);
        audit.event(AuditAction.RETENTION_POLICY_CHANGED).target("RETENTION_POLICY", type).detail("from", before).detail("to", days)
                .reason(reason).success();
        return list().stream().filter(p -> p.dataType().equals(type)).findFirst().orElseThrow();
    }

    @Scheduled(cron = "0 17 * * * *")
    public void scheduled() {
        runAll("system");
    }

    /** Runs every policy; one failure does not stop the others. */
    public synchronized List<RunResult> runAll(String actor) {
        Instant now = clock.instant();
        List<RunResult> results = new java.util.ArrayList<>();
        for (String type : STORES.keySet()) {
            int d = days(type);
            Instant cutoff = now.minus(Duration.ofDays(d));
            long deleted = 0;
            String error = null;
            try {
                deleted = tx.execute(s -> purge(type, cutoff, d));
            } catch (RuntimeException e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.error("Retention for {} failed: {}", type, error);
            }
            jdbc.update("UPDATE retention_policies SET last_run_at = ?, last_deleted = ?, last_error = ? WHERE data_type = ?",
                    Timestamp.from(now), deleted, error == null ? null : error.substring(0, Math.min(500, error.length())), type);
            results.add(new RunResult(type, deleted, error));
        }
        long total = results.stream().mapToLong(RunResult::deleted).sum();
        boolean failed = results.stream().anyMatch(r -> r.error() != null);
        if (total > 0 || failed || !"system".equals(actor)) {
            var ev = audit.event(AuditAction.RETENTION_RUN).actor(null, actor).target("RETENTION", "all");
            results.forEach(r -> ev.detail(r.dataType(), r.error() == null ? r.deleted() : "error: " + r.error()));
            if (failed) ev.failure(); else ev.success();
        }
        if (total > 0) log.info("Retention run by {} deleted {} records", actor, total);
        return results;
    }

    private long purge(String type, Instant cutoff, int days) {
        Timestamp c = Timestamp.from(cutoff);
        return switch (type) {
            case "METRICS_RAW" -> {
                lastMetrics = metrics.enforce(Duration.ofDays(days), Duration.ofDays(days("METRICS_ROLLUP")));
                yield lastMetrics[0];
            }
            case "METRICS_ROLLUP" -> {
                long n = lastMetrics != null ? lastMetrics[1] : metrics.enforce(Duration.ofDays(days("METRICS_RAW")), Duration.ofDays(days))[1];
                lastMetrics = null;
                yield n;
            }
            case "EVENTS" -> jdbc.update("DELETE FROM events WHERE occurred_at < ?", c);
            case "ALERTS_RESOLVED" -> jdbc.update("DELETE FROM alerts WHERE status = 'RESOLVED' AND resolved_at < ?", c);
            case "FLOWS" -> jdbc.update("DELETE FROM network_flows WHERE flow_end < ?", c);
            case "CAPTURES" -> captures.purgeOlderThan(cutoff, days);
            case "FIREWALL_HISTORY" -> jdbc.update("DELETE FROM firewall_rules WHERE status IN ('REMOVED','EXPIRED','REJECTED','CANCELLED')"
                    + " AND updated_at < ?", c);
            case "AUDIT_LOGS" -> {
                Long n = jdbc.queryForObject("SELECT purge_audit_logs(?)", Long.class, c);
                yield n == null ? 0 : n;
            }
            default -> 0;
        };
    }
}
