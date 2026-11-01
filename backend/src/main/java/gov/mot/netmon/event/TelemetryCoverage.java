package gov.mot.netmon.event;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.event.EventDtos.Coverage;
import gov.mot.netmon.event.EventDtos.Gap;
import gov.mot.netmon.metrics.MetricNames;

/**
 * Finds which parts of a time window have metric data for a server, using the CPU series as the
 * heartbeat of collected data. Any spacing larger than 2.5 report intervals is a gap.
 */
@Component
public class TelemetryCoverage {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration rawRetention;

    public TelemetryCoverage(JdbcTemplate jdbc, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.rawRetention = props.monitoring().rawRetention();
    }

    public Coverage coverage(UUID serverId, Instant from, Instant to) {
        Instant now = clock.instant();
        if (to.isAfter(now)) to = now;
        if (!from.isBefore(to)) from = to.minusSeconds(60);
        Integer configured = jdbc.query("SELECT report_interval_seconds FROM server_agent_configs WHERE server_id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, serverId);
        boolean raw = !from.isBefore(now.minus(rawRetention));
        int interval = raw ? (configured == null ? 30 : configured) : 300;
        List<Instant> times = raw
                ? jdbc.query("SELECT collected_at FROM metric_samples WHERE server_id = ? AND metric = ? AND resource = '' "
                        + "AND collected_at BETWEEN ? AND ? ORDER BY collected_at",
                        (rs, i) -> rs.getTimestamp(1).toInstant(), serverId, MetricNames.CPU_USAGE, Timestamp.from(from),
                        Timestamp.from(to))
                : jdbc.query("SELECT bucket_start FROM metric_rollups_5m WHERE server_id = ? AND metric = ? AND resource = '' "
                        + "AND bucket_start BETWEEN ? AND ? ORDER BY bucket_start",
                        (rs, i) -> rs.getTimestamp(1).toInstant(), serverId, MetricNames.CPU_USAGE,
                        Timestamp.from(from.minusSeconds(300)), Timestamp.from(to));
        long tolerance = Math.round(interval * 2.5);
        List<Gap> gaps = new ArrayList<>();
        Instant cursor = from;
        for (Instant t : times) {
            if (Duration.between(cursor, t).toSeconds() > tolerance) gaps.add(new Gap(cursor, t));
            if (t.isAfter(cursor)) cursor = t;
        }
        if (Duration.between(cursor, to).toSeconds() > tolerance) gaps.add(new Gap(cursor, to));
        long expected = Math.max(1, Duration.between(from, to).toSeconds() / interval);
        double pct = Math.min(100.0, Math.round(times.size() * 1000.0 / expected) / 10.0);
        return new Coverage(from, to, interval, times.size(), expected, pct, gaps, raw ? "raw" : "5m");
    }
}
