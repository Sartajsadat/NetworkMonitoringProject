package gov.mot.netmon.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.support.AgentTestSupport;
import gov.mot.netmon.user.RoleCode;

class OperationsApiTest extends AgentTestSupport {

    String root;
    String infra;
    String analyst;

    @BeforeEach
    void setUp() throws Exception {
        root = tokenFor("root", RoleCode.SUPER_ADMIN);
        infra = tokenFor("infra", RoleCode.INFRASTRUCTURE_ADMIN);
        analyst = tokenFor("ana", RoleCode.SECURITY_ANALYST);
    }

    @AfterEach
    void resetPolicies() {
        jdbc.update("UPDATE retention_policies SET updated_by = 'system', last_error = NULL");
        jdbc.update("UPDATE retention_policies SET retention_days = 180 WHERE data_type = 'EVENTS'");
        jdbc.update("UPDATE retention_policies SET retention_days = 1095 WHERE data_type = 'AUDIT_LOGS'");
    }

    Timestamp ago(Duration d) {
        return Timestamp.from(Instant.now().minus(d));
    }

    void audit(Timestamp at, String action) {
        jdbc.update("INSERT INTO audit_logs (occurred_at, actor_username, action, result) VALUES (?, 'test', ?, 'SUCCESS')", at, action);
    }

    @Test
    void policiesAreListedChangedByASuperAdminWithinLimitsAndAudited() throws Exception {
        mvc.perform(get("/api/v1/retention-policies").header("Authorization", analyst)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/retention-policies").header("Authorization", infra)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8)).andExpect(jsonPath("$[0].dataType").value("METRICS_RAW"))
                .andExpect(jsonPath("$[?(@.dataType=='EVENTS')].fromEnvironment").value(true))
                .andExpect(jsonPath("$[?(@.dataType=='AUDIT_LOGS')].minDays").value(365));
        String body = toJson(Map.of("retentionDays", 30, "reason", "Storage is limited on the lab server"));
        mvc.perform(put("/api/v1/retention-policies/EVENTS").header("Authorization", infra).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/retention-policies/EVENTS").header("Authorization", root).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.retentionDays").value(30)).andExpect(jsonPath("$.fromEnvironment").value(false))
                .andExpect(jsonPath("$.updatedBy").value("root"));
        mvc.perform(put("/api/v1/retention-policies/AUDIT_LOGS").header("Authorization", root).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("retentionDays", 30, "reason", "Try to shorten the audit log")))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RETENTION_OUT_OF_RANGE"));
        assertThat(auditCount("RETENTION_POLICY_CHANGED", "SUCCESS")).isPositive();
    }

    @Test
    void runDeletesOnlyExpiredDataAndNeverRecentAuditHistory() throws Exception {
        String sid = createServer(root, "old-01");
        jdbc.update("INSERT INTO events (occurred_at, received_at, category, event_type, severity, source, summary) VALUES "
                + "(?, now(), 'SYSTEM', 'TEST', 'INFO', 'PLATFORM', 'old'), (?, now(), 'SYSTEM', 'TEST', 'INFO', 'PLATFORM', 'new')",
                ago(Duration.ofDays(200)), ago(Duration.ofDays(1)));
        jdbc.update("INSERT INTO network_flows (received_at, flow_start, flow_end, src_ip, dst_ip, protocol, bytes, packets, version) VALUES "
                + "(now(), ?, ?, '10.0.0.1', '10.0.0.2', 6, 1, 1, 9)", ago(Duration.ofDays(30)), ago(Duration.ofDays(30)));
        audit(ago(Duration.ofDays(1200)), "TEST_VERY_OLD");
        audit(ago(Duration.ofDays(400)), "TEST_OLD");
        audit(ago(Duration.ofDays(10)), "TEST_RECENT");

        // The database refuses to delete audit entries younger than a year, whatever the application asks.
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_logs WHERE action = 'TEST_RECENT'")).hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("SELECT purge_audit_logs(now())", Long.class)).isGreaterThanOrEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'TEST_RECENT'", Long.class)).isEqualTo(1);
        audit(ago(Duration.ofDays(400)), "TEST_OLD");

        audit(ago(Duration.ofDays(1200)), "TEST_VERY_OLD");
        mvc.perform(post("/api/v1/retention-policies/run").header("Authorization", infra)).andExpect(status().isForbidden());
        JsonNode results = body(mvc.perform(post("/api/v1/retention-policies/run").header("Authorization", root)).andExpect(status().isOk())
                .andReturn());
        assertThat(result(results, "EVENTS")).isEqualTo(1);
        assertThat(result(results, "FLOWS")).isEqualTo(1);
        assertThat(result(results, "AUDIT_LOGS")).isEqualTo(1); // 1200 days old; 400 days is inside the 3-year policy
        assertThat(jdbc.queryForObject("SELECT count(*) FROM events WHERE summary = 'new'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action IN ('TEST_OLD','TEST_RECENT')", Long.class)).isEqualTo(2);
        mvc.perform(get("/api/v1/retention-policies").header("Authorization", root))
                .andExpect(jsonPath("$[?(@.dataType=='EVENTS')].lastDeleted").value(1))
                .andExpect(jsonPath("$[?(@.dataType=='EVENTS')].lastRunAt").value(Matchers.everyItem(Matchers.notNullValue())));
        assertThat(auditCount("RETENTION_RUN", "SUCCESS")).isPositive();
        assertThat(sid).isNotNull();
    }

    static long result(JsonNode results, String type) {
        for (JsonNode r : results) if (r.get("dataType").asText().equals(type)) return r.get("deleted").asLong();
        return -1;
    }

    @Test
    void platformDiskAlertFollowsTheMeasuredDiskUsage() throws Exception {
        var health = applicationContext.getBean(SystemHealthService.class);
        Double used = health.captureDiskUsedPercent();
        org.junit.jupiter.api.Assumptions.assumeTrue(used != null, "capture disk not measurable here");
        jdbc.update("UPDATE alert_rules SET enabled = true, threshold = 50 WHERE rule_type = 'PLATFORM_DISK_LOW'");
        applicationContext.getBean(gov.mot.netmon.alert.AlertEngine.class).evaluateAll();
        long open = jdbc.queryForObject("SELECT count(*) FROM alerts WHERE rule_type = 'PLATFORM_DISK_LOW' AND status = 'OPEN'", Long.class);
        assertThat(open).isEqualTo(used >= 50 ? 1L : 0L);
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.context.ApplicationContext applicationContext;

    @Test
    void healthReportsMeasuredValuesAndWeakSettings() throws Exception {
        mvc.perform(get("/api/v1/system/health").header("Authorization", analyst)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/system/health").header("Authorization", infra)).andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value(Matchers.oneOf("OK", "WARNING", "ERROR")))
                .andExpect(jsonPath("$.database.version").value(Matchers.startsWith("16")))
                .andExpect(jsonPath("$.database.schemaVersion").value("8"))
                .andExpect(jsonPath("$.checks[?(@.key=='DATABASE')].status").value("OK"))
                .andExpect(jsonPath("$.largestTables.length()").value(8))
                .andExpect(jsonPath("$.configuration.REFRESH_COOKIE_SECURE").exists())
                .andExpect(jsonPath("$.configuration.jwtSecret").doesNotExist());
    }
}
