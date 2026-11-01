package gov.mot.netmon.metrics;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.agent.EnrollmentTokenRepository;
import gov.mot.netmon.config.AppProperties;

/**
 * Builds 5-minute rollups and enforces retention. Designed for a single backend instance
 * (a scheduler lock is needed before running several replicas).
 */
@Component
public class MetricRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(MetricRetentionJob.class);
    /** Recompute this much recent history each run so late (buffered) reports are included. */
    static final Duration ROLLUP_LOOKBACK = Duration.ofHours(3);

    private final JdbcTemplate jdbc;
    private final EnrollmentTokenRepository tokens;
    private final Clock clock;
    private final AppProperties.Monitoring props;

    public MetricRetentionJob(JdbcTemplate jdbc, EnrollmentTokenRepository tokens, Clock clock, AppProperties appProperties) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.clock = clock;
        this.props = appProperties.monitoring();
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void rollup() {
        Instant now = clock.instant();
        rollupBetween(now.minus(ROLLUP_LOOKBACK), now.plus(Duration.ofMinutes(5)));
    }

    /** Idempotent: re-running for the same window recomputes the same buckets. */
    public int rollupBetween(Instant since, Instant until) {
        int rows = jdbc.update("""
                INSERT INTO metric_rollups_5m (server_id, metric, resource, bucket_start, avg_value, min_value, max_value, sample_count)
                SELECT server_id, metric, resource,
                       date_bin('5 minutes', collected_at, TIMESTAMPTZ '2000-01-01 00:00:00+00') AS bucket,
                       avg(value), min(value), max(value), count(*)
                  FROM metric_samples
                 WHERE collected_at >= date_bin('5 minutes', ?::timestamptz, TIMESTAMPTZ '2000-01-01 00:00:00+00')
                   AND collected_at < ?
                 GROUP BY server_id, metric, resource, bucket
                ON CONFLICT (server_id, metric, resource, bucket_start) DO UPDATE
                   SET avg_value = EXCLUDED.avg_value, min_value = EXCLUDED.min_value,
                       max_value = EXCLUDED.max_value, sample_count = EXCLUDED.sample_count""",
                Timestamp.from(since), Timestamp.from(until));
        log.debug("Rolled up {} buckets", rows);
        return rows;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
    @Transactional
    public void enforceRetention() {
        Instant now = clock.instant();
        // Re-check the window about to be deleted (covers periods when the 5-minute job did not run).
        // Cut on a 5-minute boundary so whole buckets are deleted; a half-deleted bucket would otherwise
        // be re-rolled from partial data.
        long cut = now.minus(props.rawRetention()).getEpochSecond();
        Instant rawCutoff = Instant.ofEpochSecond(cut - Math.floorMod(cut, 300));
        rollupBetween(rawCutoff.minus(Duration.ofHours(2)), rawCutoff.plus(Duration.ofMinutes(5)));
        int raw = jdbc.update("DELETE FROM metric_samples WHERE collected_at < ?", Timestamp.from(rawCutoff));
        int rolled = jdbc.update("DELETE FROM metric_rollups_5m WHERE bucket_start < ?",
                Timestamp.from(now.minus(props.rollupRetention())));
        int expiredTokens = tokens.deleteExpiredBefore(now.minus(Duration.ofDays(7)));
        if (raw + rolled + expiredTokens > 0) {
            log.info("Retention: removed {} raw samples, {} rollups, {} expired enrollment tokens", raw, rolled, expiredTokens);
        }
    }
}
