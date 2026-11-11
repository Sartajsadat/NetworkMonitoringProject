package gov.mot.netmon.ops;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.flow.FlowCollector;

/**
 * Monitoring of the monitoring platform itself: database, capture storage, background jobs,
 * agents, and settings that weaken security. Every value is measured; nothing is assumed.
 */
@Service
public class SystemHealthService {

    public record Check(String key, String status, String message) {
    }

    public record TableSize(String table, long bytes, long rows) {
    }

    public record Health(Instant time, String overall, List<Check> checks, Map<String, Object> runtime, Map<String, Object> database,
            List<TableSize> largestTables, Map<String, Object> storage, Map<String, Object> agents, Map<String, Object> jobs,
            Map<String, Object> configuration) {
    }

    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final FlowCollector flows;
    private final Clock clock;
    private final gov.mot.netmon.user.PasswordPolicy passwordPolicy;

    public SystemHealthService(JdbcTemplate jdbc, AppProperties props, FlowCollector flows, Clock clock,
            gov.mot.netmon.user.PasswordPolicy passwordPolicy) {
        this.passwordPolicy = passwordPolicy;
        this.jdbc = jdbc;
        this.props = props;
        this.flows = flows;
        this.clock = clock;
    }

    /** Used share (%) of the file system holding capture files; null if it cannot be read. */
    public Double captureDiskUsedPercent() {
        File dir = new File(props.capture().storageDir());
        File probe = dir.exists() ? dir : dir.getParentFile();
        if (probe == null || probe.getTotalSpace() == 0) return null;
        return 100.0 * (probe.getTotalSpace() - probe.getUsableSpace()) / probe.getTotalSpace();
    }

    public Health health() {
        Instant now = clock.instant();
        List<Check> checks = new ArrayList<>();

        Map<String, Object> runtime = new LinkedHashMap<>();
        Runtime rt = Runtime.getRuntime();
        runtime.put("version", getClass().getPackage().getImplementationVersion());
        runtime.put("javaVersion", System.getProperty("java.version"));
        runtime.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        runtime.put("heapUsedBytes", rt.totalMemory() - rt.freeMemory());
        runtime.put("heapMaxBytes", rt.maxMemory());
        runtime.put("processors", rt.availableProcessors());
        runtime.put("timeZone", java.util.TimeZone.getDefault().getID());
        double heap = 100.0 * (rt.totalMemory() - rt.freeMemory()) / rt.maxMemory();
        if (heap > 90) checks.add(new Check("HEAP", "WARNING", String.format("Java heap %.0f%% used", heap)));

        Map<String, Object> db = new LinkedHashMap<>();
        List<TableSize> tables = List.of();
        try {
            db.put("version", jdbc.queryForObject("SHOW server_version", String.class));
            db.put("sizeBytes", jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class));
            db.put("connections", jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()", Long.class));
            db.put("maxConnections", jdbc.queryForObject("SHOW max_connections", String.class));
            Instant dbNow = jdbc.queryForObject("SELECT now()", Timestamp.class).toInstant();
            long skew = Math.abs(Duration.between(dbNow, now).toMillis());
            db.put("clockSkewMillis", skew);
            if (skew > 5000) checks.add(new Check("CLOCK", "WARNING",
                    "Backend and database clocks differ by " + skew / 1000 + " s. Synchronise time (NTP): event correlation depends on it."));
            Long migration = jdbc.queryForObject("SELECT max(installed_rank) FROM flyway_schema_history WHERE success", Long.class);
            db.put("schemaVersion", jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE installed_rank = ?", String.class, migration));
            tables = jdbc.query("""
                    SELECT c.relname, pg_total_relation_size(c.oid) AS bytes, GREATEST(c.reltuples, 0)::bigint AS rows
                      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname = current_schema() AND c.relkind = 'r'
                     ORDER BY bytes DESC LIMIT 8""", (rs, i) -> new TableSize(rs.getString(1), rs.getLong(2), rs.getLong(3)));
            checks.add(new Check("DATABASE", "OK", "PostgreSQL " + db.get("version") + " reachable"));
        } catch (RuntimeException e) {
            checks.add(new Check("DATABASE", "ERROR", "Database query failed: " + e.getMessage()));
        }

        Map<String, Object> storage = new LinkedHashMap<>();
        File dir = new File(props.capture().storageDir());
        File probe = dir.exists() ? dir : dir.getParentFile();
        storage.put("captureDirectory", props.capture().storageDir());
        storage.put("captureQuotaBytes", props.capture().quotaBytes());
        Long used = jdbc.queryForObject("SELECT COALESCE(sum(bytes_stored), 0) FROM packet_captures WHERE files_deleted_at IS NULL", Long.class);
        storage.put("captureUsedBytes", used);
        if (probe != null && probe.getTotalSpace() > 0) {
            storage.put("diskTotalBytes", probe.getTotalSpace());
            storage.put("diskFreeBytes", probe.getUsableSpace());
            double pct = captureDiskUsedPercent();
            storage.put("diskUsedPercent", pct);
            if (pct >= 95) checks.add(new Check("DISK", "ERROR", String.format("Capture disk %.1f%% full", pct)));
            else if (pct >= 85) checks.add(new Check("DISK", "WARNING", String.format("Capture disk %.1f%% full", pct)));
            else checks.add(new Check("DISK", "OK", String.format("Capture disk %.1f%% used", pct)));
        } else {
            checks.add(new Check("DISK", "WARNING", "Capture directory " + props.capture().storageDir() + " cannot be read"));
        }
        if (used != null && props.capture().quotaBytes() > 0 && used * 100 / props.capture().quotaBytes() >= 80) {
            checks.add(new Check("CAPTURE_QUOTA", "WARNING", "Capture files use " + used * 100 / props.capture().quotaBytes() + "% of the quota"));
        }

        Map<String, Object> agents = new LinkedHashMap<>();
        Instant fresh = now.minus(props.monitoring().onlineThreshold());
        agents.put("active", jdbc.queryForObject("SELECT count(*) FROM agents WHERE status = 'ACTIVE'", Long.class));
        agents.put("reporting", jdbc.queryForObject("SELECT count(*) FROM agents WHERE status = 'ACTIVE' AND last_seen_at > ?", Long.class,
                Timestamp.from(fresh)));
        agents.put("versions", jdbc.queryForList("SELECT agent_version AS version, count(*) AS n FROM agents WHERE status = 'ACTIVE'"
                + " GROUP BY agent_version ORDER BY agent_version"));
        long silent = (Long) agents.get("active") - (Long) agents.get("reporting");
        if (silent > 0) checks.add(new Check("AGENTS", "WARNING", silent + " enrolled agent(s) are not reporting"));

        Map<String, Object> jobs = new LinkedHashMap<>();
        jobs.put("retentionLastRun", jdbc.queryForObject("SELECT min(last_run_at) FROM retention_policies", Timestamp.class));
        List<String> failing = jdbc.queryForList("SELECT data_type FROM retention_policies WHERE last_error IS NOT NULL", String.class);
        if (!failing.isEmpty()) checks.add(new Check("RETENTION", "ERROR", "Retention failed for " + String.join(", ", failing)));
        Timestamp lastRet = (Timestamp) jobs.get("retentionLastRun");
        if (lastRet != null && lastRet.toInstant().isBefore(now.minus(Duration.ofHours(3)))) {
            checks.add(new Check("RETENTION", "WARNING", "Retention has not run for more than 3 hours"));
        }
        jobs.put("pendingAgentTasks", jdbc.queryForObject("SELECT count(*) FROM agent_tasks WHERE status = 'PENDING'", Long.class));
        jobs.put("oldestPendingTask", jdbc.queryForObject("SELECT min(created_at) FROM agent_tasks WHERE status = 'PENDING'", Timestamp.class));
        jobs.put("lastAlertEvaluation", jdbc.queryForObject("SELECT max(last_triggered_at) FROM alerts", Timestamp.class));
        jobs.put("flowCollectorEnabled", props.flows().enabled());
        jobs.put("flowCollectorRunning", flows.isRunning());
        if (props.flows().enabled() && !flows.isRunning()) checks.add(new Check("FLOWS", "ERROR", "Flow collector is enabled but not listening"));

        // Keyed by the environment variable that sets each value (.env).
        Map<String, Object> cfg = new LinkedHashMap<>();
        var sec = props.security();
        cfg.put("ACCESS_TOKEN_TTL", sec.accessTokenTtl().toString());
        cfg.put("REFRESH_TOKEN_TTL", sec.refreshTokenTtl().toString());
        cfg.put("REFRESH_COOKIE_SECURE", sec.refreshCookieSecure());
        cfg.put("MAX_FAILED_LOGINS", sec.maxFailedLogins());
        cfg.put("LOCKOUT_DURATION", sec.lockoutDuration().toString());
        cfg.put("LOGIN_RATE_LIMIT_PER_MINUTE", sec.loginRateLimitPerMinute());
        cfg.put("CORS_ALLOWED_ORIGINS", sec.corsAllowedOrigins());
        cfg.put("AGENT_ONLINE_THRESHOLD", props.monitoring().onlineThreshold().toString());
        cfg.put("CAPTURE_MAX_DURATION_SECONDS", props.capture().maxDurationSeconds());
        cfg.put("FIREWALL_PROTECTED_CIDRS", props.firewall().protectedCidrs());
        cfg.put("FIREWALL_TASK_TIMEOUT", props.firewall().taskTimeout().toString());
        cfg.put("FLOW_COLLECTOR_ENABLED", props.flows().enabled());
        cfg.put("LAB_WEAK_PASSWORDS", passwordPolicy.isLabWeakPasswords());
        if (passwordPolicy.isLabWeakPasswords()) checks.add(new Check("PASSWORDS", "WARNING",
                "LAB_WEAK_PASSWORDS=true: the password policy is off. Lab only - remove it before real use."));
        if (!sec.refreshCookieSecure()) checks.add(new Check("COOKIE", "WARNING",
                "REFRESH_COOKIE_SECURE=false: the session cookie may travel over plain HTTP. Use only for local testing."));
        if (sec.corsAllowedOrigins() != null && sec.corsAllowedOrigins().stream().anyMatch(o -> o.contains("*"))) {
            checks.add(new Check("CORS", "WARNING", "CORS allows a wildcard origin"));
        }
        String overall = checks.stream().anyMatch(c -> "ERROR".equals(c.status())) ? "ERROR"
                : checks.stream().anyMatch(c -> "WARNING".equals(c.status())) ? "WARNING" : "OK";
        return new Health(now, overall, checks, runtime, db, tables, storage, agents, jobs, cfg);
    }
}
