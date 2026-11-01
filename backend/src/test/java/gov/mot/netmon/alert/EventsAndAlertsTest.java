package gov.mot.netmon.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.event.AgentConnectionMonitor;
import gov.mot.netmon.support.AgentTestSupport;
import gov.mot.netmon.user.RoleCode;

class EventsAndAlertsTest extends AgentTestSupport {

    static final String CPU_RULE = "00000000-0000-4000-8000-000000000001";

    @Autowired
    AlertEngine engine;
    @Autowired
    AgentConnectionMonitor connections;

    String infra;
    String analyst;
    String readOnly;
    String serverId;
    String agent;
    Instant t0;

    @BeforeEach
    void setUp() throws Exception {
        infra = tokenFor("infra", RoleCode.INFRASTRUCTURE_ADMIN);
        analyst = tokenFor("analyst", RoleCode.SECURITY_ANALYST);
        readOnly = tokenFor("viewer", RoleCode.READ_ONLY);
        serverId = createServer(infra, "web-01");
        agent = enroll(infra, serverId, "web-01");
        t0 = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.SECONDS);
    }

    private Map<String, Object> reportWith(Instant at, String nginxStatus, Instant nginxSince, Instant boot, String cpuCollector) {
        Map<String, Object> r = report(at, 10.0, 1000.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> host = new HashMap<>((Map<String, Object>) r.get("host"));
        host.put("bootTime", boot.toString());
        r.put("host", host);
        Map<String, Object> svc = new HashMap<>(Map.of("name", "nginx.service", "status", nginxStatus, "activeState", "x"));
        if (nginxSince != null) svc.put("since", nginxSince.toString());
        r.put("services", List.of(svc));
        r.put("collectors", List.of(Map.of("name", "cpu", "status", cpuCollector, "message", "test")));
        return r;
    }

    private void send(Map<String, Object> r) throws Exception {
        mvc.perform(post("/api/v1/agent/report").header("Authorization", agent).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(r))).andExpect(status().isOk());
    }

    private JsonNode events(String token, String query) throws Exception {
        return body(mvc.perform(get("/api/v1/events?" + query).header("Authorization", token)).andExpect(status().isOk()).andReturn());
    }

    private List<String> types(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("content").forEach(e -> out.add(e.get("type").asText()));
        return out;
    }

    private void cpuSamples(double value, int count) {
        Instant now = Instant.now();
        jdbc.update("DELETE FROM metric_samples WHERE server_id = ?::uuid AND metric = 'cpu.usage_pct'", serverId);
        for (int i = 0; i < count; i++) {
            jdbc.update("INSERT INTO metric_samples (server_id, metric, resource, collected_at, value) VALUES (?::uuid, 'cpu.usage_pct', '', ?, ?)",
                    serverId, Timestamp.from(now.minusSeconds(10 + 25L * i)), value);
        }
    }

    private JsonNode activeAlerts(String token) throws Exception {
        return body(mvc.perform(get("/api/v1/alerts").header("Authorization", token)).andExpect(status().isOk()).andReturn());
    }

    @Test
    void reportsBecomeServiceRebootAndCollectorEvents() throws Exception {
        Instant boot = t0.minusSeconds(3600);
        send(reportWith(t0, "RUNNING", t0.minusSeconds(600), boot, "OK"));
        send(reportWith(t0.plusSeconds(30), "FAILED", t0.plusSeconds(20), boot, "OK"));
        send(reportWith(t0.plusSeconds(60), "RUNNING", t0.plusSeconds(45), boot, "ERROR"));
        send(reportWith(t0.plusSeconds(90), "RUNNING", t0.plusSeconds(85), t0.plusSeconds(80), "OK"));
        JsonNode page = events(infra, "serverId=" + serverId + "&order=asc&from=" + t0.minusSeconds(60));
        assertThat(types(page)).containsSubsequence("SERVICE_STATE_CHANGED", "SERVICE_STATE_CHANGED", "COLLECTOR_PROBLEM",
                "SERVER_REBOOTED", "COLLECTOR_RECOVERED");
        JsonNode failed = page.get("content").get(0);
        assertThat(failed.get("severity").asText()).isEqualTo("ERROR");
        assertThat(failed.get("resource").asText()).isEqualTo("nginx.service");
        assertThat(failed.get("occurredAt").asText()).isEqualTo(t0.plusSeconds(20).toString()); // the agent's "since" time
        assertThat(failed.get("details").get("to").asText()).isEqualTo("FAILED");
        // filters: service and minimum severity
        assertThat(events(infra, "service=nginx.service&from=" + t0.minusSeconds(60)).get("totalElements").asLong()).isEqualTo(2);
        assertThat(types(events(infra, "minSeverity=ERROR&from=" + t0.minusSeconds(60)))).containsExactly("SERVICE_STATE_CHANGED");
    }

    @Test
    void cpuAlertOpensDeduplicatesResolvesAndRespectsCooldown() throws Exception {
        send(report(Instant.now().minusSeconds(5), 95.0, 1000.0));
        cpuSamples(95, 10);
        engine.evaluateAll();
        engine.evaluateAll();
        JsonNode list = activeAlerts(infra);
        assertThat(list.get("totalElements").asLong()).isEqualTo(1);
        JsonNode a = list.get("content").get(0);
        assertThat(a.get("ruleType").asText()).isEqualTo("CPU_HIGH");
        assertThat(a.get("status").asText()).isEqualTo("OPEN");
        assertThat(a.get("evaluations").asInt()).isEqualTo(2);
        assertThat(a.get("condition").asText()).contains("95.0%").contains("threshold 90.0%");
        // the timeline has the threshold event, linked to the alert
        JsonNode ev = events(infra, "type=CPU_HIGH").get("content").get(0);
        assertThat(ev.get("alertId").asText()).isEqualTo(a.get("id").asText());
        assertThat(ev.get("alertStatus").asText()).isEqualTo("OPEN");
        assertThat(events(infra, "alertStatus=ACTIVE").get("totalElements").asLong()).isEqualTo(1);

        cpuSamples(20, 10);
        engine.evaluateAll();
        assertThat(activeAlerts(infra).get("totalElements").asLong()).isZero();
        mvc.perform(get("/api/v1/alerts/" + a.get("id").asText()).header("Authorization", readOnly))
                .andExpect(jsonPath("$.alert.status").value("RESOLVED")).andExpect(jsonPath("$.alert.resolutionType").value("AUTO"))
                .andExpect(jsonPath("$.alert.peakValue").value(95.0))
                .andExpect(jsonPath("$.events[?(@.type=='ALERT_RESOLVED')]").exists());

        cpuSamples(97, 10);
        engine.evaluateAll();
        assertThat(activeAlerts(infra).get("totalElements").asLong()).as("cooldown of 15 min").isZero();

        // lowering the cooldown applies at once
        Map<String, Object> rule = new HashMap<>(Map.of("name", "High CPU usage", "ruleType", "CPU_HIGH", "severity", "WARNING",
                "threshold", 90, "windowSeconds", 300, "cooldownSeconds", 0, "enabled", true));
        mvc.perform(put("/api/v1/alert-rules/" + CPU_RULE).header("Authorization", infra).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(rule))).andExpect(status().isOk());
        engine.evaluateAll();
        assertThat(activeAlerts(infra).get("totalElements").asLong()).isEqualTo(1);
        assertThat(auditCount("ALERT_RULE_UPDATED", "SUCCESS")).isPositive();
    }

    @Test
    void acknowledgeAndResolveAreRoleCheckedAndAudited() throws Exception {
        send(report(Instant.now().minusSeconds(5), 95.0, 1000.0));
        cpuSamples(96, 10);
        engine.evaluateAll();
        String id = activeAlerts(infra).get("content").get(0).get("id").asText();
        mvc.perform(post("/api/v1/alerts/" + id + "/acknowledge").header("Authorization", readOnly)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/alerts/" + id + "/acknowledge").header("Authorization", infra).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("comment", "Looking at the batch job")))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED")).andExpect(jsonPath("$.acknowledgedBy").value("infra"));
        mvc.perform(post("/api/v1/alerts/" + id + "/acknowledge").header("Authorization", infra)).andExpect(status().isConflict());
        engine.evaluateAll(); // still firing: stays acknowledged, not a new alert
        assertThat(activeAlerts(infra).get("content").get(0).get("status").asText()).isEqualTo("ACKNOWLEDGED");
        mvc.perform(post("/api/v1/alerts/" + id + "/resolve").header("Authorization", infra).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("comment", "")))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/alerts/" + id + "/resolve").header("Authorization", infra).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("comment", "Batch job rescheduled")))).andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionType").value("MANUAL"));
        assertThat(auditCount("ALERT_ACKNOWLEDGED", "SUCCESS")).isPositive();
        assertThat(auditCount("ALERT_RESOLVED", "SUCCESS")).isPositive();
        assertThat(types(events(infra, "correlationId=" + id))).contains("CPU_HIGH", "ALERT_ACKNOWLEDGED", "ALERT_RESOLVED");
        mvc.perform(get("/api/v1/alerts/counts").header("Authorization", readOnly)).andExpect(jsonPath("$.open").value(0));
    }

    @Test
    void disconnectionIsReportedOnceThenPairedWithReconnectionAndGapsAreShown() throws Exception {
        Instant now = Instant.now();
        send(report(now.minusSeconds(5), 10.0, 1000.0));
        cpuSamples(10, 4); // data only for the last ~2 minutes
        jdbc.update("UPDATE agents SET last_seen_at = ? WHERE server_id = ?::uuid", Timestamp.from(now.minusSeconds(900)), serverId);
        connections.detectDisconnections();
        connections.detectDisconnections();
        JsonNode down = events(infra, "type=AGENT_DISCONNECTED");
        assertThat(down.get("totalElements").asLong()).isEqualTo(1);
        engine.evaluateAll();
        assertThat(activeAlerts(infra).get("content").get(0).get("ruleType").asText()).isEqualTo("AGENT_OFFLINE");

        send(report(Instant.now(), 10.0, 1000.0));
        JsonNode up = events(infra, "type=AGENT_RECONNECTED").get("content").get(0);
        assertThat(up.get("correlationId").asText()).isEqualTo(down.get("content").get(0).get("correlationId").asText());
        engine.evaluateAll();
        assertThat(activeAlerts(infra).get("totalElements").asLong()).isZero();

        long downId = down.get("content").get(0).get("id").asLong();
        JsonNode ctx = body(mvc.perform(get("/api/v1/events/" + downId + "/context").header("Authorization", infra))
                .andExpect(status().isOk()).andReturn());
        assertThat(ctx.get("related").toString()).contains("AGENT_RECONNECTED");
        assertThat(ctx.get("coverage").get("gaps").size()).isPositive(); // no metrics while it was silent
        assertThat(ctx.get("coverage").get("percent").asDouble()).isLessThan(50);
    }

    @Test
    void securityEventsAndAlertsAreOnlyForSecurityRoles() throws Exception {
        createUser("clerk", RoleCode.READ_ONLY);
        for (int i = 0; i < 10; i++) login("clerk", "wrong-password-" + i);
        login("nobody-here", "x");
        JsonNode sec = events(analyst, "category=SECURITY&size=50");
        assertThat(types(sec)).contains("LOGIN_FAILED", "ACCOUNT_LOCKED");
        assertThat(sec.toString()).contains("clerk").doesNotContain("nobody-here"); // unknown names are not repeated
        assertThat(events(readOnly, "category=SECURITY").get("totalElements").asLong()).isZero();
        long id = sec.get("content").get(0).get("id").asLong();
        mvc.perform(get("/api/v1/events/" + id).header("Authorization", readOnly)).andExpect(status().isNotFound());

        engine.evaluateAll();
        JsonNode alerts = activeAlerts(analyst);
        assertThat(alerts.toString()).contains("FAILED_LOGINS");
        assertThat(activeAlerts(readOnly).toString()).doesNotContain("FAILED_LOGINS");
    }

    @Test
    void histogramCountsBySeverityAndCategory() throws Exception {
        Instant boot = t0.minusSeconds(3600);
        send(reportWith(t0, "RUNNING", t0.minusSeconds(600), boot, "OK"));
        send(reportWith(t0.plusSeconds(30), "STOPPED", t0.plusSeconds(20), boot, "OK"));
        JsonNode h = body(mvc.perform(get("/api/v1/events/histogram?serverId=" + serverId + "&from=" + t0.minusSeconds(3600)
                + "&to=" + Instant.now()).header("Authorization", readOnly)).andExpect(status().isOk()).andReturn());
        assertThat(h.get("total").asLong()).isEqualTo(2); // the service stop and the agent enrollment
        assertThat(h.get("bySeverity").get("WARNING").asLong()).isEqualTo(1);
        assertThat(h.get("byCategory").get("AGENT").asLong()).isEqualTo(1);
        assertThat(h.get("byCategory").get("SERVICE").asLong()).isEqualTo(1);
        assertThat(h.get("bucketSeconds").asLong()).isEqualTo(60);
        mvc.perform(get("/api/v1/events?from=2026-01-02T00:00:00Z&to=2026-01-01T00:00:00Z").header("Authorization", readOnly))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rulesAreValidatedAndOnlyAdminsManageThem() throws Exception {
        String netAdmin = tokenFor("net", RoleCode.NETWORK_ADMIN);
        Map<String, Object> rule = new HashMap<>(Map.of("name", "Disk on /var", "ruleType", "DISK_USAGE_HIGH", "severity", "ERROR",
                "threshold", 85, "windowSeconds", 300, "cooldownSeconds", 600, "resourceFilter", "/var", "enabled", true));
        mvc.perform(post("/api/v1/alert-rules").header("Authorization", netAdmin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(rule))).andExpect(status().isForbidden());
        Map<String, Object> bad = new HashMap<>(rule);
        bad.put("threshold", 150);
        mvc.perform(post("/api/v1/alert-rules").header("Authorization", infra).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(bad))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_THRESHOLD"));
        Map<String, Object> scoped = new HashMap<>(rule);
        scoped.put("ruleType", "CAPTURE_STORAGE_HIGH");
        scoped.put("serverId", serverId);
        mvc.perform(post("/api/v1/alert-rules").header("Authorization", infra).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(scoped))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/alert-rules").header("Authorization", infra).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(rule))).andExpect(status().isCreated()).andExpect(jsonPath("$.resourceFilter").value("/var"));
        mvc.perform(get("/api/v1/alert-rules").header("Authorization", readOnly)).andExpect(jsonPath("$.length()").value(12));
        mvc.perform(get("/api/v1/alert-rules/types").header("Authorization", readOnly))
                .andExpect(jsonPath("$[?(@.type=='TRAFFIC_SPIKE')].unit").value("RATIO"));
        assertThat(auditCount("ALERT_RULE_CREATED", "SUCCESS")).isPositive();
    }

    @Test
    void captureLifecycleIsCorrelatedOnTheTimeline() throws Exception {
        String netAdmin = tokenFor("net2", RoleCode.NETWORK_ADMIN);
        Map<String, Object> r = report(Instant.now(), 5.0, 100.0);
        r.put("capture", Map.of("enabled", true, "available", true, "interfaces", List.of(Map.of("name", "eth0")),
                "maxDurationSeconds", 600, "maxBytes", 104857600));
        send(r);
        String capture = body(mvc.perform(post("/api/v1/captures").header("Authorization", netAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("serverId", serverId, "name", "web check",
                        "reason", "Checking slow pages for the team", "interfaceName", "eth0", "durationSeconds", 60,
                        "maxMegabytes", 10)))).andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(post("/api/v1/agent/tasks").header("Authorization", agent)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/agent/captures/" + capture + "/status").header("Authorization", agent)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("state", "RUNNING")))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/agent/captures/" + capture + "/status").header("Authorization", agent)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("state", "COMPLETED", "packets", 12))))
                .andExpect(status().isOk());
        JsonNode chain = events(netAdmin, "correlationId=" + capture + "&order=asc");
        assertThat(types(chain)).containsExactly("CAPTURE_REQUESTED", "CAPTURE_STARTED", "CAPTURE_COMPLETED");
        assertThat(chain.get("content").get(2).get("captureId").asText()).isEqualTo(capture);
        assertThat(events(readOnly, "category=CAPTURE").get("totalElements").asLong()).isZero();
        UUID.fromString(capture);
    }
}
