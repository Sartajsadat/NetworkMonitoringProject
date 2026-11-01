package gov.mot.netmon.event;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import gov.mot.netmon.config.AppProperties;

/** Deletes events and resolved alerts after their retention periods. */
@Component
public class EventRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(EventRetentionJob.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration eventRetention;
    private final Duration alertRetention;

    public EventRetentionJob(JdbcTemplate jdbc, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.eventRetention = props.events().retention();
        this.alertRetention = props.alerts().resolvedRetention();
    }

    @Scheduled(cron = "0 17 * * * *")
    public void purge() {
        int events = jdbc.update("DELETE FROM events WHERE occurred_at < ?", Timestamp.from(clock.instant().minus(eventRetention)));
        int alerts = jdbc.update("DELETE FROM alerts WHERE status = 'RESOLVED' AND resolved_at < ?",
                Timestamp.from(clock.instant().minus(alertRetention)));
        if (events + alerts > 0) log.info("Retention: deleted {} events and {} resolved alerts", events, alerts);
    }
}
