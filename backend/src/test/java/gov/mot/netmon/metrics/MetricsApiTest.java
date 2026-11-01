package gov.mot.netmon.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import gov.mot.netmon.support.AgentTestSupport;
import gov.mot.netmon.user.RoleCode;

class MetricsApiTest extends AgentTestSupport {

    @Autowired
    MetricRetentionJob retention;

    private String admin;
    private String serverId;
    private String agent;

    @BeforeEach
    void setUp() throws Exception {
        admin = tokenFor("infra", RoleCode.INFRASTRUCTURE_ADMIN);
        serverId = createServer(admin, "web-01");
        agent = enroll(admin, serverId, "web-01");
    }

    private void send(Instant at, double cpu) throws Exception {
        mvc.perform(post("/api/v1/agent/report").header("Authorization", agent).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(report(at, cpu, 1000.0)))).andExpect(status().isOk());
    }

    private void insertSample(Instant at, String metric, double v) {
        jdbc.update("INSERT INTO metric_samples (server_id, metric, resource, collected_at, value) VALUES (?, ?, '', ?, ?)",
                UUID.fromString(serverId), metric, Timestamp.from(at), v);
    }

    @Test
    void rawHistoryForShortRanges() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        send(now.minusSeconds(60), 10);
        send(now.minusSeconds(30), 20);
        send(now, 30);
        mvc.perform(get("/api/v1/servers/" + serverId + "/metrics").header("Authorization", admin)
                .param("metric", "cpu.usage_pct", "net.rx_bps")
                .param("from", now.minus(Duration.ofMinutes(15)).toString())
                .param("to", now.plusSeconds(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("RAW"))
                .andExpect(jsonPath("$.expectedIntervalSeconds").value(30))
                .andExpect(jsonPath("$.series[0].metric").value("cpu.usage_pct"))
                .andExpect(jsonPath("$.series[0].unit").value("percent"))
                .andExpect(jsonPath("$.series[0].points.length()").value(3))
                .andExpect(jsonPath("$.series[0].points[2].v").value(30.0))
                .andExpect(jsonPath("$.series[1].resource").value("eth0"));
    }

    @Test
    void validatesMetricsAndRange() throws Exception {
        mvc.perform(get("/api/v1/servers/" + serverId + "/metrics").header("Authorization", admin)
                .param("metric", "drop table")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_METRIC"));
        Instant now = Instant.now();
        mvc.perform(get("/api/v1/servers/" + serverId + "/metrics").header("Authorization", admin)
                .param("metric", "cpu.usage_pct").param("from", now.minus(Duration.ofDays(120)).toString())
                .param("to", now.toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"));
    }

    @Test
    void longRangesUseFiveMinuteRollupsWithMinMax() throws Exception {
        Instant bucket = Instant.now().minus(Duration.ofDays(2)).truncatedTo(ChronoUnit.HOURS);
        insertSample(bucket.plusSeconds(10), "cpu.usage_pct", 10);
        insertSample(bucket.plusSeconds(40), "cpu.usage_pct", 30);
        insertSample(bucket.plusSeconds(70), "cpu.usage_pct", 50);
        retention.rollupBetween(bucket.minus(Duration.ofHours(1)), bucket.plus(Duration.ofHours(1)));
        retention.rollupBetween(bucket.minus(Duration.ofHours(1)), bucket.plus(Duration.ofHours(1))); // idempotent

        mvc.perform(get("/api/v1/servers/" + serverId + "/metrics").header("Authorization", admin)
                .param("metric", "cpu.usage_pct").param("from", Instant.now().minus(Duration.ofDays(3)).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("5m"))
                .andExpect(jsonPath("$.expectedIntervalSeconds").value(300))
                .andExpect(jsonPath("$.series[0].points.length()").value(1))
                .andExpect(jsonPath("$.series[0].points[0].v").value(30.0))
                .andExpect(jsonPath("$.series[0].points[0].min").value(10.0))
                .andExpect(jsonPath("$.series[0].points[0].max").value(50.0));
        Integer count = jdbc.queryForObject("SELECT sample_count FROM metric_rollups_5m", Integer.class);
        assertThat(count).isEqualTo(3);
    }

    @Test
    void retentionDeletesOldRawSamplesButKeepsRollups() throws Exception {
        Instant old = Instant.now().minus(Duration.ofDays(8)).truncatedTo(ChronoUnit.HOURS);
        insertSample(old, "cpu.usage_pct", 42);
        retention.rollupBetween(old.minus(Duration.ofHours(1)), old.plus(Duration.ofHours(1)));
        insertSample(Instant.now(), "cpu.usage_pct", 7);
        retention.enforceRetention();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM metric_samples", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT avg_value FROM metric_rollups_5m WHERE bucket_start <= ?", Double.class,
                Timestamp.from(old))).isEqualTo(42.0);
    }

    @Test
    void latestShowsNoAgentBeforeEnrollmentAndFleetAfterReports() throws Exception {
        String other = createServer(admin, "db-02");
        mvc.perform(get("/api/v1/servers/" + other + "/metrics/latest").header("Authorization", admin))
                .andExpect(jsonPath("$.available").value(false)).andExpect(jsonPath("$.reason").value("NO_AGENT"));
        send(Instant.now(), 40);
        mvc.perform(get("/api/v1/monitoring/fleet").header("Authorization", tokenFor("ro-user", RoleCode.READ_ONLY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("web-01"))
                .andExpect(jsonPath("$[0].cpu.usagePercent").value(40.0))
                .andExpect(jsonPath("$[0].processes").doesNotExist());
    }

    @Test
    void olderBufferedReportDoesNotReplaceNewerSnapshot() throws Exception {
        Instant now = Instant.now();
        send(now, 50);
        send(now.minus(Duration.ofMinutes(10)), 5);
        mvc.perform(get("/api/v1/servers/" + serverId + "/metrics/latest").header("Authorization", admin))
                .andExpect(jsonPath("$.report.cpu.usagePercent").value(50.0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM metric_samples WHERE metric='cpu.usage_pct'", Long.class)).isEqualTo(2);
    }

    @Test
    void dashboardReportsFleetAggregates() throws Exception {
        mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", admin))
                .andExpect(jsonPath("$.fleet.reportingServers").value(0))
                .andExpect(jsonPath("$.fleet.avgCpuPercent").doesNotExist())
                .andExpect(jsonPath("$.modules[0].availability").value("NOT_COLLECTED"));
        send(Instant.now(), 60);
        mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", admin))
                .andExpect(jsonPath("$.connection.ONLINE").value(1))
                .andExpect(jsonPath("$.fleet.reportingServers").value(1))
                .andExpect(jsonPath("$.fleet.avgCpuPercent").value(60.0))
                .andExpect(jsonPath("$.fleet.totalRxBitsPerSec").value(1000.0))
                .andExpect(jsonPath("$.modules[0].availability").value("AVAILABLE"));
    }
}
