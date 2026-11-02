package gov.mot.netmon.firewall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.alert.AlertEngine;
import gov.mot.netmon.support.AgentTestSupport;
import gov.mot.netmon.user.RoleCode;

class FirewallApiTest extends AgentTestSupport {

    @Autowired
    FirewallJobs jobs;
    @Autowired
    AlertEngine alertEngine;

    String superAdmin;
    String netAdmin;
    String analyst;
    String serverId;
    String agent;
    Instant clock = Instant.now().minusSeconds(600);

    @BeforeEach
    void setUp() throws Exception {
        superAdmin = tokenFor("root", RoleCode.SUPER_ADMIN);
        netAdmin = tokenFor("neta", RoleCode.NETWORK_ADMIN);
        analyst = tokenFor("ana", RoleCode.SECURITY_ANALYST);
        serverId = createServer(superAdmin, "web-01");
        agent = enroll(superAdmin, serverId, "web-01.mot.local");
    }

    /** Agent reports with increasing collection times. */
    ResultActions agentReport(boolean enforcement, List<String> tags) throws Exception {
        clock = clock.plusSeconds(1).isAfter(Instant.now()) ? clock.plusSeconds(1) : Instant.now();
        Map<String, Object> r = report(clock, 5.0, 1.0);
        Map<String, Object> fw = new HashMap<>();
        fw.put("enabled", true);
        fw.put("enforcementAllowed", enforcement);
        fw.put("backend", "NFTABLES");
        fw.put("message", "nftables (table inet netmon)");
        fw.put("protectedAddresses", List.of("10.0.0.5/32"));
        fw.put("ruleTags", tags);
        r.put("firewall", fw);
        return mvc.perform(post("/api/v1/agent/report").header("Authorization", agent).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(r))).andExpect(status().isOk());
    }

    ResultActions call(String token, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, Object body)
            throws Exception {
        req.header("Authorization", token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(toJson(body));
        return mvc.perform(req);
    }

    String integration() throws Exception {
        return body(call(netAdmin, post("/api/v1/firewall-integrations"), Map.of("serverId", serverId))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    Map<String, Object> rule(String integrationId, String remote, String port, Instant expires, boolean ack) {
        Map<String, Object> m = new HashMap<>();
        m.put("integrationId", integrationId);
        m.put("action", "BLOCK");
        m.put("direction", "INBOUND");
        m.put("remote", remote);
        m.put("protocol", port == null ? "ANY" : "TCP");
        if (port != null) m.put("localPort", port);
        m.put("reason", "Brute force attempts against the web login (ticket SEC-12)");
        if (expires != null) m.put("expiresAt", expires.toString());
        m.put("acknowledgeWarnings", ack);
        return m;
    }

    JsonNode tasks() throws Exception {
        return body(mvc.perform(post("/api/v1/agent/tasks").header("Authorization", agent)).andExpect(status().isOk()).andReturn())
                .get("tasks");
    }

    ResultActions agentStatus(String ruleId, String state, String msg) throws Exception {
        Map<String, Object> m = new HashMap<>(Map.of("state", state));
        if (msg != null) m.put("message", msg);
        return mvc.perform(post("/api/v1/agent/firewall/rules/" + ruleId + "/status").header("Authorization", agent)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(m)));
    }

    String ruleStatus(String ruleId) throws Exception {
        return body(call(analyst, get("/api/v1/firewall-rules/" + ruleId), null).andExpect(status().isOk()).andReturn())
                .get("rule").get("status").asText();
    }

    long events(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM events WHERE event_type = ?", Long.class, type);
    }

    @Test
    void dryRunWorkflowWithFourEyesApproval() throws Exception {
        agentReport(false, List.of());
        String id = integration();
        call(analyst, get("/api/v1/firewall-integrations/" + id), null).andExpect(jsonPath("$.mode").value("DRY_RUN"))
                .andExpect(jsonPath("$.target.backend").value("NFTABLES")).andExpect(jsonPath("$.enforcing").value(false));

        Instant exp = Instant.now().plus(Duration.ofDays(2));
        call(analyst, post("/api/v1/firewall-rules/preview"), rule(id, "203.0.113.0/24", "443", exp, false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.mode").value("DRY_RUN"))
                .andExpect(jsonPath("$.scope").value(org.hamcrest.Matchers.containsString("not a network-wide block"))).andExpect(jsonPath("$.scopeKind").value("SERVER")).andExpect(jsonPath("$.warnings[0].code").value("DRY_RUN"))
                .andExpect(jsonPath("$.command").value(org.hamcrest.Matchers.containsString("ip saddr 203.0.113.0/24 tcp dport 443")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM firewall_rules", Long.class)).isZero();

        // Warnings (dry run) must be acknowledged.
        call(analyst, post("/api/v1/firewall-rules"), rule(id, "203.0.113.0/24", "443", exp, false)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WARNINGS_NOT_ACKNOWLEDGED"));
        JsonNode r = body(call(analyst, post("/api/v1/firewall-rules"), rule(id, "203.0.113.0/24", "443", exp, true))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.requestedBy").value("ana")).andReturn());
        String ruleId = r.get("id").asText();
        assertThat(r.get("preview").asText()).contains("netmon:" + r.get("tag").asText());

        // Analysts cannot approve; nobody approves their own request.
        call(analyst, post("/api/v1/firewall-rules/" + ruleId + "/approve"), Map.of()).andExpect(status().isForbidden());
        String own = body(call(netAdmin, post("/api/v1/firewall-rules"), rule(id, "198.51.100.9", "22", exp, true))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        call(netAdmin, post("/api/v1/firewall-rules/" + own + "/approve"), Map.of()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_APPROVAL"));
        call(superAdmin, post("/api/v1/firewall-rules/" + own + "/reject"), Map.of("comment", "Port 22 is needed by the vendor"))
                .andExpect(jsonPath("$.status").value("REJECTED"));

        call(netAdmin, post("/api/v1/firewall-rules/" + ruleId + "/approve"), Map.of("comment", "ok"))
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.decidedBy").value("neta"))
                .andExpect(jsonPath("$.modeAtApply").value("DRY_RUN"));
        JsonNode t = tasks();
        assertThat(t).hasSize(1);
        assertThat(t.get(0).get("type").asText()).isEqualTo("APPLY_FIREWALL_RULE");
        assertThat(t.get(0).get("firewall").get("mode").asText()).isEqualTo("DRY_RUN");
        assertThat(t.get(0).get("firewall").get("localPort").asText()).isEqualTo("443");
        assertThat(tasks()).isEmpty();

        // Still APPROVED until the agent reports - the task being sent proves nothing.
        assertThat(ruleStatus(ruleId)).isEqualTo("APPROVED");
        agentStatus(ruleId, "DRY_RUN", "nft -c accepted the rule").andExpect(status().isOk());
        assertThat(ruleStatus(ruleId)).isEqualTo("DRY_RUN");
        JsonNode detail = body(call(analyst, get("/api/v1/firewall-rules/" + ruleId), null).andReturn());
        assertThat(detail.get("approvals").get(0).get("decidedBy").asText()).isEqualTo("neta");

        // Removing a dry-run rule needs no agent work.
        call(netAdmin, post("/api/v1/firewall-rules/" + ruleId + "/remove"), Map.of("reason", "Attack stopped"))
                .andExpect(jsonPath("$.status").value("REMOVED"));
        assertThat(auditCount("FIREWALL_RULE_APPROVED", "SUCCESS")).isPositive();
        assertThat(events("FIREWALL_RULE_REQUESTED")).isEqualTo(2);
    }

    @Test
    void enforcementNeedsSuperAdminAndTheServersConsent() throws Exception {
        agentReport(false, List.of());
        String id = integration();
        Map<String, String> enforce = Map.of("mode", "ENFORCE", "reason", "Lab test approved by CAB-7");
        call(netAdmin, put("/api/v1/firewall-integrations/" + id + "/mode"), enforce).andExpect(status().isForbidden());
        call(superAdmin, put("/api/v1/firewall-integrations/" + id + "/mode"), enforce).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENFORCEMENT_NOT_ALLOWED"));
        agentReport(true, List.of());
        long modeChanges = auditCount("FIREWALL_MODE_CHANGED", "SUCCESS");
        call(superAdmin, put("/api/v1/firewall-integrations/" + id + "/mode"), enforce).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("ENFORCE")).andExpect(jsonPath("$.enforcing").value(true));
        assertThat(auditCount("FIREWALL_MODE_CHANGED", "SUCCESS")).isEqualTo(modeChanges + 1);

        // Protected addresses: the agent's own platform address and the configured list.
        call(analyst, post("/api/v1/firewall-rules"), rule(id, "10.0.0.0/24", "443", null, true)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("10.0.0.5/32")));
        call(analyst, post("/api/v1/firewall-rules"), rule(id, "0.0.0.0/0", null, null, true)).andExpect(status().isBadRequest());

        String ruleId = body(call(analyst, post("/api/v1/firewall-rules"), rule(id, "203.0.113.7", "8080",
                Instant.now().plus(Duration.ofHours(4)), false)).andExpect(status().isCreated()).andReturn()).get("id").asText();
        call(netAdmin, post("/api/v1/firewall-rules/" + ruleId + "/approve"), null).andExpect(jsonPath("$.modeAtApply").value("ENFORCE"));
        JsonNode task = tasks().get(0).get("firewall");
        assertThat(task.get("mode").asText()).isEqualTo("ENFORCE");
        String tag = task.get("tag").asText();

        agentStatus(ruleId, "APPLIED", "Read back from nftables (handle 4)").andExpect(status().isOk());
        assertThat(ruleStatus(ruleId)).isEqualTo("APPLIED");
        agentStatus(ruleId, "REMOVED", null).andExpect(status().isConflict()); // no removal pending

        // Drift: the rule disappears from the server, then comes back.
        agentReport(true, List.of(tag));
        assertThat(ruleStatus(ruleId)).isEqualTo("APPLIED");
        agentReport(true, List.of());
        assertThat(ruleStatus(ruleId)).isEqualTo("UNVERIFIED");
        assertThat(events("FIREWALL_RULE_UNVERIFIED")).isEqualTo(1);
        alertEngine.evaluateAll();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alerts WHERE rule_type = 'FIREWALL_DRIFT' AND status = 'OPEN'", Long.class))
                .isEqualTo(1);
        agentReport(true, List.of(tag, "deadbeef"));
        assertThat(ruleStatus(ruleId)).isEqualTo("APPLIED");
        assertThat(events("FIREWALL_UNKNOWN_RULE")).isEqualTo(1);
        agentReport(true, List.of(tag, "deadbeef"));
        assertThat(events("FIREWALL_UNKNOWN_RULE")).isEqualTo(1); // reported once
        alertEngine.evaluateAll();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alerts WHERE rule_type = 'FIREWALL_DRIFT' AND status = 'RESOLVED'", Long.class))
                .isEqualTo(1);

        // Removal goes through the agent and finishes only when it confirms.
        call(netAdmin, post("/api/v1/firewall-rules/" + ruleId + "/remove"), Map.of("reason", "Incident closed"))
                .andExpect(jsonPath("$.status").value("REMOVAL_PENDING"));
        JsonNode rm = tasks().get(0);
        assertThat(rm.get("type").asText()).isEqualTo("REMOVE_FIREWALL_RULE");
        agentStatus(ruleId, "REMOVED", "Deleted handle 4").andExpect(status().isOk());
        assertThat(ruleStatus(ruleId)).isEqualTo("REMOVED");
        call(netAdmin, delete(id), null).andExpect(status().isNoContent());
    }

    static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder delete(String id) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/firewall-integrations/" + id);
    }

    @Test
    void expiryTimeoutsAndLocalRefusals() throws Exception {
        agentReport(true, List.of());
        String id = integration();
        call(superAdmin, put("/api/v1/firewall-integrations/" + id + "/mode"), Map.of("mode", "ENFORCE", "reason", "Lab test"))
                .andExpect(status().isOk());
        Instant exp = Instant.now().plus(Duration.ofHours(1));
        String a = body(call(analyst, post("/api/v1/firewall-rules"), rule(id, "203.0.113.1", "443", exp, true)).andReturn()).get("id").asText();
        String b = body(call(analyst, post("/api/v1/firewall-rules"), rule(id, "203.0.113.2", "443", exp, true)).andReturn()).get("id").asText();
        String c = body(call(analyst, post("/api/v1/firewall-rules"), rule(id, "203.0.113.3", "443", exp, true)).andReturn()).get("id").asText();
        for (String r : List.of(a, b, c)) call(netAdmin, post("/api/v1/firewall-rules/" + r + "/approve"), null).andExpect(status().isOk());
        assertThat(tasks()).hasSize(3);
        agentStatus(a, "APPLIED", null).andExpect(status().isOk());
        // The server's agent only did a dry run although the platform asked for enforcement.
        agentStatus(b, "DRY_RUN", "firewall.enforcement is false on this server").andExpect(status().isOk());
        assertThat(ruleStatus(b)).isEqualTo("FAILED");

        // Expiry: the applied rule is removed through the agent and ends EXPIRED.
        jdbc.update("UPDATE firewall_rules SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", a);
        jobs.run();
        assertThat(ruleStatus(a)).isEqualTo("REMOVAL_PENDING");
        assertThat(tasks().get(0).get("type").asText()).isEqualTo("REMOVE_FIREWALL_RULE");
        agentStatus(a, "NOT_PRESENT", null).andExpect(status().isOk());
        assertThat(ruleStatus(a)).isEqualTo("EXPIRED");

        // The agent never answers: FAILED, not applied.
        jdbc.update("UPDATE firewall_rules SET updated_at = now() - interval '2 hours' WHERE id = ?::uuid", c);
        jobs.run();
        assertThat(ruleStatus(c)).isEqualTo("FAILED");

        // Another agent cannot report on this server's rules.
        String other = createServer(superAdmin, "db-01");
        String otherAgent = enroll(superAdmin, other, "db-01.mot.local");
        mvc.perform(post("/api/v1/agent/firewall/rules/" + a + "/status").header("Authorization", otherAgent)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("state", "APPLIED")))).andExpect(status().isNotFound());
    }

    @Test
    void centralFirewallStaysDryRunAndReadOnlyUsersOnlyView() throws Exception {
        String viewer = tokenFor("viewer", RoleCode.READ_ONLY);
        String id = body(call(netAdmin, post("/api/v1/firewall-integrations"), Map.of("name", "Core firewall (model to be confirmed)"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.kind").value("CENTRAL_DRY_RUN")).andReturn()).get("id").asText();
        call(superAdmin, put("/api/v1/firewall-integrations/" + id + "/mode"), Map.of("mode", "ENFORCE", "reason", "try it"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_SUPPORTED"));
        call(analyst, post("/api/v1/firewall-rules/preview"), rule(id, "203.0.113.0/24", "443", null, false))
                .andExpect(jsonPath("$.scope").value(org.hamcrest.Matchers.containsString("dry run only")));
        String ruleId = body(call(analyst, post("/api/v1/firewall-rules"), rule(id, "203.0.113.0/24", "443", null, true))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        call(viewer, post("/api/v1/firewall-rules"), rule(id, "203.0.113.0/24", "443", null, true)).andExpect(status().isForbidden());
        call(viewer, get("/api/v1/firewall-rules"), null).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1));
        call(netAdmin, post("/api/v1/firewall-rules/" + ruleId + "/approve"), null).andExpect(jsonPath("$.status").value("DRY_RUN"))
                .andExpect(jsonPath("$.statusMessage").value(org.hamcrest.Matchers.containsString("Dry run only")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_tasks", Long.class)).isZero();
        // The request that was never decided lapses at its expiry.
        String lapsed = body(call(analyst, post("/api/v1/firewall-rules"), rule(id, "203.0.113.99", "443",
                Instant.now().plusSeconds(3600), true)).andReturn()).get("id").asText();
        jdbc.update("UPDATE firewall_rules SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", lapsed);
        jobs.run();
        assertThat(ruleStatus(lapsed)).isEqualTo("EXPIRED");
    }
}
