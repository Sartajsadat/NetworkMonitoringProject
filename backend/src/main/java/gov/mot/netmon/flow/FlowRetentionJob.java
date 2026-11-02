package gov.mot.netmon.flow;

import java.sql.Timestamp;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import gov.mot.netmon.config.AppProperties;

@Component
public class FlowRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(FlowRetentionJob.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AppProperties props;

    public FlowRetentionJob(JdbcTemplate jdbc, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.props = props;
    }

    @Scheduled(cron = "0 23 * * * *")
    public void purge() {
        int n = jdbc.update("DELETE FROM network_flows WHERE flow_end < ?",
                Timestamp.from(clock.instant().minus(props.flows().retention())));
        if (n > 0) log.info("Retention: deleted {} flow records", n);
    }
}
