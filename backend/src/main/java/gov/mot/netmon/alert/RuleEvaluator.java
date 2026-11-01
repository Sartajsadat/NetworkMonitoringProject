package gov.mot.netmon.alert;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import gov.mot.netmon.agent.AgentProtocol;
import gov.mot.netmon.alert.Evaluation.Hit;
import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.event.ReportEventDetector;
import gov.mot.netmon.metrics.MetricNames;
import gov.mot.netmon.metrics.SnapshotStore;

/**
 * Reads the data each rule type needs and decides where its condition holds. Metric rules use the
 * average over the rule's window; state rules (service, collectors) must hold for the whole window.
 */
@Component
public class RuleEvaluator {

    private static final String LOOPBACK = "(s.resource <> 'lo' AND s.resource NOT ILIKE 'loopback%')";
    /** Below this, a "traffic spike" ratio is noise (1 Mb/s). */
    static final double SPIKE_MIN_BPS = 1_000_000;

    private final NamedParameterJdbcTemplate jdbc;
    private final SnapshotStore snapshots;
    private final Clock clock;
    private final AppProperties props;
    /** When a state condition (service down, collector failing) was first seen, per alert key. */
    private final Map<String, Instant> pendingSince = new ConcurrentHashMap<>();

    public RuleEvaluator(NamedParameterJdbcTemplate jdbc, SnapshotStore snapshots, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.clock = clock;
        this.props = props;
    }

    public Evaluation evaluate(AlertRule rule, Map<UUID, String> serverNames) {
        Evaluation ev = new Evaluation();
        switch (rule.getRuleType()) {
            case CPU_HIGH -> metric(rule, ev, serverNames, List.of(MetricNames.CPU_USAGE), false, "CPU usage");
            case MEMORY_HIGH -> metric(rule, ev, serverNames, List.of(MetricNames.MEM_USED_PCT), false, "Memory usage");
            case SWAP_HIGH -> metric(rule, ev, serverNames, List.of(MetricNames.SWAP_USED_PCT), false, "Swap usage");
            case DISK_USAGE_HIGH -> metric(rule, ev, serverNames, List.of(MetricNames.DISK_USED_PCT), true, "Disk usage");
            case NETWORK_THROUGHPUT_HIGH -> metric(rule, ev, serverNames, List.of(MetricNames.NET_RX_BPS, MetricNames.NET_TX_BPS),
                    true, "Throughput");
            case INTERFACE_ERRORS -> metric(rule, ev, serverNames, List.of(MetricNames.NET_ERRORS_PS, MetricNames.NET_DROPS_PS),
                    true, "Errors and drops");
            case TRAFFIC_SPIKE -> spike(rule, ev, serverNames);
            case AGENT_OFFLINE -> offline(rule, ev, serverNames);
            case SERVICE_NOT_RUNNING, AGENT_DEGRADED -> state(rule, ev, serverNames);
            case FAILED_LOGINS -> logins(rule, ev);
            case CAPTURE_STORAGE_HIGH -> storage(rule, ev);
        }
        return ev;
    }

    private MapSqlParameterSource scope(AlertRule rule, StringBuilder where) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (rule.getServerId() != null) {
            where.append(" AND s.server_id = :sid");
            p.addValue("sid", rule.getServerId());
        }
        if (rule.getResourceFilter() != null && !rule.getResourceFilter().isBlank()) {
            where.append(" AND s.resource = :res");
            p.addValue("res", rule.getResourceFilter());
        }
        return p;
    }

    /**
     * Average of each metric over the window, per server and resource. With two metrics (rx/tx,
     * errors/drops): throughput takes the larger direction, errors add up.
     */
    private void metric(AlertRule rule, Evaluation ev, Map<UUID, String> names, List<String> metrics, boolean perResource,
            String label) {
        Instant now = clock.instant();
        int window = Math.max(60, rule.getWindowSeconds());
        StringBuilder where = new StringBuilder();
        MapSqlParameterSource p = scope(rule, where);
        p.addValue("metrics", metrics).addValue("since", Timestamp.from(now.minusSeconds(window)));
        boolean network = rule.getRuleType().category == gov.mot.netmon.event.EventCategory.NETWORK;
        Map<String, double[]> agg = new HashMap<>(); // key server|resource -> [combined, minSamples]
        Map<String, UUID> serverOf = new HashMap<>();
        Map<String, String> resourceOf = new HashMap<>();
        jdbc.query("""
                SELECT s.server_id, s.resource, s.metric, avg(s.value) AS v, count(*) AS n, COALESCE(c.report_interval_seconds, 30) AS iv
                FROM metric_samples s JOIN servers sv ON sv.id = s.server_id
                LEFT JOIN server_agent_configs c ON c.server_id = s.server_id
                WHERE s.metric IN (:metrics) AND s.collected_at > :since AND sv.monitoring_status IN ('ACTIVE', 'PENDING_AGENT')"""
                + (network ? " AND " + LOOPBACK : "") + where + " GROUP BY s.server_id, s.resource, s.metric, c.report_interval_seconds",
                p, rs -> {
                    UUID sid = rs.getObject("server_id", UUID.class);
                    String res = rs.getString("resource");
                    long minSamples = Math.max(1, (long) Math.floor(window / (double) rs.getInt("iv") * 0.5));
                    if (rs.getLong("n") < minSamples) return; // too little data in the window to decide
                    String k = sid + "|" + res;
                    serverOf.put(k, sid);
                    resourceOf.put(k, res);
                    double v = rs.getDouble("v");
                    double[] a = agg.computeIfAbsent(k, x -> new double[] {0});
                    a[0] = rule.getRuleType() == AlertRuleType.INTERFACE_ERRORS ? a[0] + v : Math.max(a[0], v);
                });
        for (var e : agg.entrySet()) {
            UUID sid = serverOf.get(e.getKey());
            String res = perResource ? resourceOf.get(e.getKey()) : null;
            ev.checked.add(sid);
            double v = e.getValue()[0];
            if (v >= rule.getThreshold()) {
                ev.fire(rule, new Hit(sid, names.get(sid), res, v,
                        label + (res == null ? "" : " on " + res) + " averaged " + fmt(v, rule) + " over " + minutes(window)
                                + " (threshold " + fmt(rule.getThreshold(), rule) + ")"));
            } else {
                ev.value(rule, sid, res, v);
            }
        }
    }

    /** Traffic in the window compared with the average of the 24 hours before it. */
    private void spike(AlertRule rule, Evaluation ev, Map<UUID, String> names) {
        Instant now = clock.instant();
        int window = Math.max(60, rule.getWindowSeconds());
        StringBuilder where = new StringBuilder();
        MapSqlParameterSource p = scope(rule, where);
        p.addValue("metrics", List.of(MetricNames.NET_RX_BPS, MetricNames.NET_TX_BPS))
                .addValue("since", Timestamp.from(now.minusSeconds(window)))
                .addValue("base", Timestamp.from(now.minusSeconds(window).minus(Duration.ofHours(24))));
        jdbc.query("""
                SELECT s.server_id, s.resource,
                       avg(s.value) FILTER (WHERE s.collected_at > :since) * 2 AS cur,
                       avg(s.value) FILTER (WHERE s.collected_at <= :since) * 2 AS base,
                       count(*) FILTER (WHERE s.collected_at <= :since) AS nbase,
                       count(*) FILTER (WHERE s.collected_at > :since) AS ncur
                FROM metric_samples s JOIN servers sv ON sv.id = s.server_id
                WHERE s.metric IN (:metrics) AND s.collected_at > :base AND sv.monitoring_status IN ('ACTIVE', 'PENDING_AGENT')
                  AND """ + LOOPBACK + where + " GROUP BY s.server_id, s.resource", p, rs -> {
                    // need at least an hour of history (2 metrics x 120 samples at 30 s) and current samples
                    if (rs.getLong("nbase") < 240 || rs.getLong("ncur") < 2) return;
                    UUID sid = rs.getObject("server_id", UUID.class);
                    String res = rs.getString("resource");
                    double cur = rs.getDouble("cur");
                    double base = rs.getDouble("base");
                    ev.checked.add(sid);
                    double ratio = base <= 0 ? (cur > 0 ? Double.MAX_VALUE : 0) : cur / base;
                    if (cur >= SPIKE_MIN_BPS && ratio >= rule.getThreshold()) {
                        double shown = Math.min(ratio, 1e6);
                        ev.fire(rule, new Hit(sid, names.get(sid), res, shown, String.format(Locale.ROOT,
                                "Traffic on %s is %.1f times its 24-hour average (%.1f Mb/s, threshold %.1fx)", res, shown,
                                cur / 1e6, rule.getThreshold())));
                    } else {
                        ev.value(rule, sid, res, ratio);
                    }
                });
    }

    private void offline(AlertRule rule, Evaluation ev, Map<UUID, String> names) {
        Instant now = clock.instant();
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = "";
        if (rule.getServerId() != null) {
            where = " AND a.server_id = :sid";
            p.addValue("sid", rule.getServerId());
        }
        jdbc.query("""
                SELECT a.server_id, a.last_seen_at FROM agents a JOIN servers sv ON sv.id = a.server_id
                WHERE a.status = 'ACTIVE' AND a.last_seen_at IS NOT NULL AND sv.monitoring_status IN ('ACTIVE', 'PENDING_AGENT')"""
                + where, p, rs -> {
                    UUID sid = rs.getObject("server_id", UUID.class);
                    Instant last = rs.getTimestamp("last_seen_at").toInstant();
                    double silent = Duration.between(last, now).toSeconds();
                    ev.checked.add(sid);
                    if (silent >= rule.getThreshold()) {
                        ev.fire(rule, new Hit(sid, names.get(sid), null, silent,
                                "No report from the agent for " + minutes((int) silent) + " (last report " + last + ")"));
                    } else {
                        ev.value(rule, sid, null, silent);
                    }
                });
    }

    /** Service and collector states from the latest report; the condition must last for the window. */
    private void state(AlertRule rule, Evaluation ev, Map<UUID, String> names) {
        Instant now = clock.instant();
        Duration fresh = props.monitoring().onlineThreshold();
        boolean service = rule.getRuleType() == AlertRuleType.SERVICE_NOT_RUNNING;
        for (SnapshotStore.Snapshot snap : snapshots.findAll().values()) {
            UUID sid = snap.serverId();
            if (!names.containsKey(sid)) continue; // not an actively monitored server
            if (rule.getServerId() != null && !rule.getServerId().equals(sid)) continue;
            if (snap.receivedAt().isBefore(now.minus(fresh))) continue; // stale: the offline rule covers this
            ev.checked.add(sid);
            AgentProtocol.Report r = snap.report();
            if (service) {
                for (AgentProtocol.Service s : r.services() == null ? List.<AgentProtocol.Service>of() : r.services()) {
                    if (!matches(rule, s.name())) continue;
                    boolean bad = "STOPPED".equals(s.status()) || "FAILED".equals(s.status()) || "NOT_FOUND".equals(s.status());
                    pending(rule, ev, sid, names.get(sid), s.name(), bad, now,
                            "Service " + s.name() + " is " + s.status());
                }
            } else {
                for (AgentProtocol.CollectorStatus c : r.collectors() == null ? List.<AgentProtocol.CollectorStatus>of() : r.collectors()) {
                    if (!matches(rule, c.name())) continue;
                    pending(rule, ev, sid, names.get(sid), c.name(), ReportEventDetector.isProblem(c.status()), now,
                            "Collector " + c.name() + ": " + c.status() + (c.message() == null ? "" : " - " + c.message()));
                }
            }
        }
    }

    private void pending(AlertRule rule, Evaluation ev, UUID sid, String name, String resource, boolean bad, Instant now,
            String condition) {
        String key = Evaluation.key(rule, sid, resource);
        if (!bad) {
            pendingSince.remove(key);
            ev.value(rule, sid, resource, null);
            return;
        }
        Instant since = pendingSince.computeIfAbsent(key, k -> now);
        if (Duration.between(since, now).toSeconds() >= rule.getWindowSeconds()) {
            ev.fire(rule, new Hit(sid, name, resource, null, condition));
        }
    }

    private static boolean matches(AlertRule rule, String resource) {
        return rule.getResourceFilter() == null || rule.getResourceFilter().isBlank() || rule.getResourceFilter().equals(resource);
    }

    private void logins(AlertRule rule, Evaluation ev) {
        Instant now = clock.instant();
        int window = Math.max(60, rule.getWindowSeconds());
        ev.checked.add(Evaluation.PLATFORM);
        jdbc.query("""
                SELECT source_ip, count(*) AS n FROM audit_logs
                WHERE action = 'AUTH_LOGIN_FAILED' AND occurred_at > :since AND source_ip IS NOT NULL GROUP BY source_ip""",
                new MapSqlParameterSource("since", Timestamp.from(now.minusSeconds(window))), rs -> {
                    String ip = rs.getString("source_ip");
                    if (!matches(rule, ip)) return;
                    double n = rs.getLong("n");
                    if (n >= rule.getThreshold()) {
                        ev.fire(rule, new Hit(null, null, ip, n, (long) n + " failed sign-ins from " + ip + " in the last "
                                + minutes(window) + " (threshold " + rule.getThreshold().longValue() + ")"));
                    }
                });
    }

    private void storage(AlertRule rule, Evaluation ev) {
        long quota = props.capture().quotaBytes();
        Long used = jdbc.queryForObject("SELECT COALESCE(sum(bytes_stored), 0) FROM packet_captures WHERE files_deleted_at IS NULL",
                new MapSqlParameterSource(), Long.class);
        ev.checked.add(Evaluation.PLATFORM);
        double pct = quota <= 0 ? 0 : (used == null ? 0 : used) * 100.0 / quota;
        if (pct >= rule.getThreshold()) {
            ev.fire(rule, new Hit(null, null, null, pct, String.format(Locale.ROOT,
                    "Capture files use %.1f%% of the %d MB quota (threshold %.0f%%)", pct, quota / (1024 * 1024),
                    rule.getThreshold())));
        } else {
            ev.value(rule, null, null, pct);
        }
    }

    static String minutes(int seconds) {
        return seconds < 120 ? seconds + " s" : (seconds / 60) + " min";
    }

    static String fmt(double v, AlertRule rule) {
        return switch (rule.getRuleType().unit) {
            case PERCENT -> String.format(Locale.ROOT, "%.1f%%", v);
            case BITS_PER_SECOND -> String.format(Locale.ROOT, "%.1f Mb/s", v / 1e6);
            case PER_SECOND -> String.format(Locale.ROOT, "%.2f/s", v);
            case RATIO -> String.format(Locale.ROOT, "%.1fx", v);
            default -> String.format(Locale.ROOT, "%.0f", v);
        };
    }
}
