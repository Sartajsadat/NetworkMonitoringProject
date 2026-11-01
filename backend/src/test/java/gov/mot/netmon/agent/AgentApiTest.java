package gov.mot.netmon.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import gov.mot.netmon.support.AgentTestSupport;
import gov.mot.netmon.user.RoleCode;

class AgentApiTest extends AgentTestSupport {

    private String admin;
    private String serverId;

    @BeforeEach
    void setUp() throws Exception {
        admin = tokenFor("infra", RoleCode.INFRASTRUCTURE_ADMIN);
        serverId = createServer(admin, "web-01");
    }

    private org.springframework.test.web.servlet.ResultActions sendReport(String agentAuth, Map<String, Object> report)
            throws Exception {
        return mvc.perform(post("/api/v1/agent/report").header("Authorization", agentAuth)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(report)));
    }

    @Test
    void enrollmentTokenIsOneTimeAndRoleProtected() throws Exception {
        String viewer = tokenFor("viewer", RoleCode.READ_ONLY);
        mvc.perform(post("/api/v1/servers/" + serverId + "/agent/enrollment-token").header("Authorization", viewer))
                .andExpect(status().isForbidden());

        String token = createToken(admin, serverId);
        mvc.perform(post("/api/v1/agent/enroll").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(enrollBody(token, "web-01")))).andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andExpect(jsonPath("$.config.reportIntervalSeconds").value(30));
        mvc.perform(post("/api/v1/agent/enroll").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(enrollBody(token, "web-01")))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ENROLLMENT_TOKEN"));
        assertThat(auditCount("AGENT_ENROLLED", "SUCCESS")).isPositive();
        assertThat(auditCount("AGENT_ENROLLMENT_FAILED", "FAILURE")).isPositive();
        // Only the hash of the secret is stored.
        Integer len = jdbc.queryForObject("SELECT length(secret_hash) FROM agents", Integer.class);
        assertThat(len).isEqualTo(64);
    }

    @Test
    void expiredOrSupersededTokensAreRejected() throws Exception {
        String first = createToken(admin, serverId);
        String second = createToken(admin, serverId); // invalidates the first
        mvc.perform(post("/api/v1/agent/enroll").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(enrollBody(first, "web-01")))).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE agent_enrollment_tokens SET expires_at = now() - interval '1 minute'");
        mvc.perform(post("/api/v1/agent/enroll").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(enrollBody(second, "web-01")))).andExpect(status().isUnauthorized());
    }

    @Test
    void reportActivatesServerAndStoresSnapshotAndMetrics() throws Exception {
        String agent = enroll(admin, serverId, "web-01");
        sendReport(agent, report(Instant.now(), 12.5, 8000.0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.config.monitoringPaused").value(false));

        mvc.perform(get("/api/v1/servers/" + serverId).header("Authorization", admin))
                .andExpect(jsonPath("$.monitoringStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.connectionStatus").value("ONLINE"))
                .andExpect(jsonPath("$.agentHealth").value("HEALTHY"))
                .andExpect(jsonPath("$.agentVersion").value("0.2.0"));
        mvc.perform(get("/api/v1/servers/" + serverId + "/metrics/latest").header("Authorization", admin))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.report.cpu.usagePercent").value(12.5))
                .andExpect(jsonPath("$.report.network[0].addresses[0]").value("10.0.0.5/24"));
        mvc.perform(get("/api/v1/servers/" + serverId + "/services").header("Authorization", admin))
                .andExpect(jsonPath("$.items[0].name").value("nginx.service"));
        mvc.perform(get("/api/v1/servers/" + serverId + "/processes").header("Authorization", admin))
                .andExpect(jsonPath("$.items[0].name").value("systemd"));

        Long cpuRows = jdbc.queryForObject("SELECT count(*) FROM metric_samples WHERE metric = 'cpu.usage_pct'", Long.class);
        Long rxRows = jdbc.queryForObject("SELECT count(*) FROM metric_samples WHERE metric = 'net.rx_bps' AND resource = 'eth0'", Long.class);
        assertThat(cpuRows).isEqualTo(1);
        assertThat(rxRows).isEqualTo(1);
    }

    @Test
    void missingValuesAreNotStoredAsZero() throws Exception {
        String agent = enroll(admin, serverId, "web-01");
        sendReport(agent, report(Instant.now(), null, null)).andExpect(status().isOk());
        Long rows = jdbc.queryForObject("SELECT count(*) FROM metric_samples WHERE metric IN ('cpu.usage_pct','net.rx_bps')", Long.class);
        assertThat(rows).isZero();
    }

    @Test
    void collectorErrorsMarkAgentDegraded() throws Exception {
        String agent = enroll(admin, serverId, "web-01");
        Map<String, Object> r = report(Instant.now(), 5.0, 1.0);
        r.put("collectors", List.of(Map.of("name", "services", "status", "PERMISSION_DENIED", "message", "D-Bus denied")));
        sendReport(agent, r).andExpect(status().isOk());
        mvc.perform(get("/api/v1/servers/" + serverId).header("Authorization", admin))
                .andExpect(jsonPath("$.agentHealth").value("DEGRADED"));
        mvc.perform(get("/api/v1/servers/" + serverId + "/agent").header("Authorization", admin))
                .andExpect(jsonPath("$.lastErrorCount").value(1));
    }

    @Test
    void rejectsBadCredentialsInvalidPayloadAndClockSkew() throws Exception {
        String agent = enroll(admin, serverId, "web-01");
        String agentId = agent.substring(6, agent.indexOf(':'));
        sendReport("Agent " + agentId + ":wrong-secret", report(Instant.now(), 1.0, 1.0)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AGENT_UNAUTHORIZED"));
        sendReport("Bearer not-an-agent", report(Instant.now(), 1.0, 1.0)).andExpect(status().isUnauthorized());
        sendReport(agent, report(Instant.now(), 150.0, 1.0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        sendReport(agent, report(Instant.now().plus(Duration.ofHours(1)), 1.0, 1.0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CLOCK_SKEW"));
        sendReport(agent, report(Instant.now().minus(Duration.ofDays(3)), 1.0, 1.0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_TOO_OLD"));
    }

    @Test
    void userAndAgentCredentialsAreNotInterchangeable() throws Exception {
        String agent = enroll(admin, serverId, "web-01");
        sendReport(admin, report(Instant.now(), 1.0, 1.0)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/servers").header("Authorization", agent)).andExpect(status().isUnauthorized());
    }

    @Test
    void revokedAgentIsRejectedAndServerShowsNoAgent() throws Exception {
        String agent = enroll(admin, serverId, "web-01");
        sendReport(agent, report(Instant.now(), 1.0, 1.0)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/servers/" + serverId + "/agent/revoke").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));
        sendReport(agent, report(Instant.now(), 1.0, 1.0)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AGENT_REVOKED"));
        mvc.perform(get("/api/v1/servers/" + serverId).header("Authorization", admin))
                .andExpect(jsonPath("$.agentHealth").value("NO_AGENT"))
                .andExpect(jsonPath("$.monitoringStatus").value("PENDING_AGENT"));
        assertThat(auditCount("AGENT_REVOKED", "SUCCESS")).isPositive();
        // Re-enrolling with a new token works and replaces the credential.
        String again = enroll(admin, serverId, "web-01");
        sendReport(again, report(Instant.now(), 1.0, 1.0)).andExpect(status().isOk());
    }

    @Test
    void credentialRotationKeepsOldSecretOnlyDuringGracePeriod() throws Exception {
        String agent = enroll(admin, serverId, "web-01");
        String agentId = agent.substring(6, agent.indexOf(':'));
        String newSecret = body(mvc.perform(post("/api/v1/agent/credentials/rotate").header("Authorization", agent))
                .andExpect(status().isOk()).andReturn()).get("secret").asText();
        String rotated = "Agent " + agentId + ":" + newSecret;
        sendReport(rotated, report(Instant.now(), 1.0, 1.0)).andExpect(status().isOk());
        sendReport(agent, report(Instant.now(), 1.0, 1.0)).andExpect(status().isOk()); // grace period
        jdbc.update("UPDATE agents SET previous_secret_valid_until = now() - interval '1 second'");
        sendReport(agent, report(Instant.now(), 1.0, 1.0)).andExpect(status().isUnauthorized());
        sendReport(rotated, report(Instant.now(), 1.0, 1.0)).andExpect(status().isOk());
    }

    @Test
    void configIsValidatedAuditedAndDeliveredToAgent() throws Exception {
        String agent = enroll(admin, serverId, "web-01");
        mvc.perform(put("/api/v1/servers/" + serverId + "/agent/config").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("reportIntervalSeconds", 15, "services", List.of("nginx.service", "postgresql@16-main.service")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.config.services.length()").value(2));
        sendReport(agent, report(Instant.now(), 1.0, 1.0)).andExpect(jsonPath("$.config.reportIntervalSeconds").value(15))
                .andExpect(jsonPath("$.config.services[0]").value("nginx.service"));
        mvc.perform(put("/api/v1/servers/" + serverId + "/agent/config").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("reportIntervalSeconds", 30, "services", List.of("nginx; rm -rf /")))))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/servers/" + serverId + "/agent/config").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("reportIntervalSeconds", 600, "services", List.of()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INTERVAL_TOO_LONG"));
        assertThat(auditCount("AGENT_CONFIG_UPDATED", "SUCCESS")).isPositive();
    }

    @Test
    void pausedServerKeepsHeartbeatButStoresNoMetrics() throws Exception {
        String agent = enroll(admin, serverId, "web-01");
        mvc.perform(put("/api/v1/servers/" + serverId).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "web-01", "hostname", "web-01.mot.local", "osFamily", "LINUX",
                        "environment", "TESTING", "organization", "Ministry IT", "monitoringStatus", "PAUSED"))))
                .andExpect(status().isOk());
        sendReport(agent, report(Instant.now(), 1.0, 1.0)).andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.config.monitoringPaused").value(true));
        Long rows = jdbc.queryForObject("SELECT count(*) FROM metric_samples", Long.class);
        assertThat(rows).isZero();
    }

    @Test
    void agentListShowsEveryServerAndHostnameMismatch() throws Exception {
        createServer(admin, "db-02");
        enroll(admin, serverId, "some-other-host");
        mvc.perform(get("/api/v1/agents").header("Authorization", tokenFor("ro-user", RoleCode.READ_ONLY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].serverName").value("db-02"))
                .andExpect(jsonPath("$[0].status").value("NOT_ENROLLED"))
                .andExpect(jsonPath("$[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].hostnameMismatch").value(true));
    }
}
