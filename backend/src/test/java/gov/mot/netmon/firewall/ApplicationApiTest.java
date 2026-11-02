package gov.mot.netmon.firewall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.support.AgentTestSupport;
import gov.mot.netmon.user.RoleCode;

class ApplicationApiTest extends AgentTestSupport {

    String root;
    String netAdmin;
    String analyst;
    String serverId;
    String agent;
    Instant clock = Instant.now().minusSeconds(60);

    @BeforeEach
    void setUp() throws Exception {
        root = tokenFor("root", RoleCode.SUPER_ADMIN);
        netAdmin = tokenFor("neta", RoleCode.NETWORK_ADMIN);
        analyst = tokenFor("ana", RoleCode.SECURITY_ANALYST);
        serverId = createServer(root, "app-01");
        agent = enroll(root, serverId, "app-01.mot.local");
    }

    ResultActions call(String token, MockHttpServletRequestBuilder req, Object body) throws Exception {
        req.header("Authorization", token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(toJson(body));
        return mvc.perform(req);
    }

    void report(List<String> appControl, List<String> tags, boolean enforce) throws Exception {
        clock = clock.plusSeconds(1).isAfter(Instant.now()) ? clock.plusSeconds(1) : Instant.now();
        Map<String, Object> r = report(clock, 5.0, 1.0);
        r.put("processes", List.of(Map.of("pid", 812, "name", "payroll-api", "user", "payroll", "cpuPercent", 1.0, "memoryBytes", 1000)));
        Map<String, Object> fw = new HashMap<>();
        fw.put("enabled", true);
        fw.put("enforcementAllowed", enforce);
        fw.put("backend", "NFTABLES");
        fw.put("message", "nftables");
        fw.put("protectedAddresses", List.of("10.0.0.5/32"));
        fw.put("ruleTags", tags);
        fw.put("applicationControl", appControl);
        r.put("firewall", fw);
        mvc.perform(post("/api/v1/agent/report").header("Authorization", agent).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(r))).andExpect(status().isOk());
    }

    String app(String name, String kind, String value) throws Exception {
        return body(call(netAdmin, post("/api/v1/applications"), Map.of("serverId", serverId, "name", name, "identityKind", kind,
                "identityValue", value, "owner", "Finance IT")).andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    Map<String, Object> policy(String appId, String mode, List<Map<String, String>> dests, boolean ack) {
        Map<String, Object> m = new HashMap<>();
        m.put("applicationId", appId);
        m.put("appMode", mode);
        m.put("destinations", dests);
        m.put("reason", "Payroll must only reach its database server");
        m.put("expiresAt", Instant.now().plusSeconds(86400).toString());
        m.put("acknowledgeWarnings", ack);
        return m;
    }

    @Test
    void inventoryValidatesIdentitiesAndShowsSupportHonestly() throws Exception {
        // A business name alone is not an identity; Windows identities do not exist on Linux.
        call(netAdmin, post("/api/v1/applications"), Map.of("serverId", serverId, "name", "Payroll", "identityKind", "WINDOWS_SERVICE",
                "identityValue", "Payroll")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDENTITY"));
        call(analyst, post("/api/v1/applications"), Map.of("serverId", serverId, "name", "Payroll", "identityKind", "LINUX_USER",
                "identityValue", "payroll")).andExpect(status().isForbidden());
        String id = app("Payroll API", "LINUX_USER", "payroll");
        call(analyst, get("/api/v1/applications/" + id), null).andExpect(jsonPath("$.support.supported").value(false))
                .andExpect(jsonPath("$.support.reason").value(Matchers.containsString("no firewall target")));
        call(netAdmin, post("/api/v1/firewall-integrations"), Map.of("serverId", serverId)).andExpect(status().isCreated());
        report(List.of(), List.of(), false); // an agent that cannot match applications (e.g. UFW)
        call(analyst, get("/api/v1/applications/" + id), null).andExpect(jsonPath("$.support.supported").value(false))
                .andExpect(jsonPath("$.support.reason").value(Matchers.containsString("cannot match applications")))
                .andExpect(jsonPath("$.seenProcesses[0]").value("payroll-api (812)"));
        call(analyst, post("/api/v1/application-policies"), policy(id, "BLOCK_INTERNET", List.of(), true))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APP_CONTROL_UNSUPPORTED"));
        report(List.of("LINUX_USER"), List.of(), false);
        call(analyst, get("/api/v1/applications/" + id), null).andExpect(jsonPath("$.support.supported").value(true));
        call(netAdmin, delete("/api/v1/applications/" + id), null).andExpect(status().isNoContent());
    }

    @Test
    void policyFollowsTheFirewallWorkflowEndToEnd() throws Exception {
        String id = app("Payroll API", "LINUX_USER", "payroll");
        String fid = body(call(netAdmin, post("/api/v1/firewall-integrations"), Map.of("serverId", serverId)).andReturn()).get("id").asText();
        report(List.of("LINUX_USER"), List.of(), true);
        call(root, put("/api/v1/firewall-integrations/" + fid + "/mode"), Map.of("mode", "ENFORCE", "reason", "Lab LAB-8"))
                .andExpect(status().isOk());
        List<Map<String, String>> db = List.of(Map.of("cidr", "10.20.0.15", "protocol", "TCP", "port", "5432"));
        call(analyst, post("/api/v1/application-policies/preview"), policy(id, "ALLOW_ONLY", db, false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.scope").value(Matchers.containsString("Only connections started by Payroll API")))
                .andExpect(jsonPath("$.command").value(Matchers.containsString("meta skuid \"payroll\" jump app_")))
                .andExpect(jsonPath("$.warnings[*].code").value(Matchers.hasItems("APP_SHARED_USER", "APP_NO_DNS")));
        call(analyst, post("/api/v1/application-policies"), policy(id, "ALLOW_ONLY", db, false)).andExpect(status().isConflict());
        JsonNode rule = body(call(analyst, post("/api/v1/application-policies"), policy(id, "ALLOW_ONLY", db, true))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.applicationLabel").value("Payroll API (Linux user payroll)"))
                .andExpect(jsonPath("$.destinations[0].cidr").value("10.20.0.15/32")).andReturn());
        String ruleId = rule.get("id").asText();
        call(analyst, get("/api/v1/firewall-rules?kind=APPLICATION&applicationId=" + id), null)
                .andExpect(jsonPath("$.content.length()").value(1));
        call(analyst, get("/api/v1/firewall-rules?kind=IP"), null).andExpect(jsonPath("$.content.length()").value(0));
        call(netAdmin, post("/api/v1/firewall-rules/" + ruleId + "/approve"), null).andExpect(jsonPath("$.status").value("APPROVED"));

        JsonNode task = body(mvc.perform(post("/api/v1/agent/tasks").header("Authorization", agent)).andReturn()).get("tasks").get(0)
                .get("firewall");
        assertThat(task.get("kind").asText()).isEqualTo("APPLICATION");
        assertThat(task.get("identityKind").asText()).isEqualTo("LINUX_USER");
        assertThat(task.get("identity").asText()).isEqualTo("payroll");
        assertThat(task.get("appMode").asText()).isEqualTo("ALLOW_ONLY");
        assertThat(task.get("destinations").get(0).get("port").asText()).isEqualTo("5432");
        assertThat(task.get("mode").asText()).isEqualTo("ENFORCE");
        String tag = task.get("tag").asText();

        mvc.perform(post("/api/v1/agent/firewall/rules/" + ruleId + "/status").header("Authorization", agent)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("state", "APPLIED", "message", "verified"))))
                .andExpect(status().isOk());
        report(List.of("LINUX_USER"), List.of(tag), true);
        call(analyst, get("/api/v1/applications/" + id), null).andExpect(jsonPath("$.activePolicies").value(1))
                .andExpect(jsonPath("$.support.enforcing").value(true));
        // Identity cannot change and the application cannot be deleted while a policy is active.
        call(netAdmin, put("/api/v1/applications/" + id), Map.of("serverId", serverId, "name", "Payroll API", "identityKind", "LINUX_USER",
                "identityValue", "other")).andExpect(status().isConflict());
        call(netAdmin, delete("/api/v1/applications/" + id), null).andExpect(status().isConflict());
        // Drift works the same as for IP rules.
        report(List.of("LINUX_USER"), List.of(), true);
        call(analyst, get("/api/v1/firewall-rules/" + ruleId), null).andExpect(jsonPath("$.rule.status").value("UNVERIFIED"));
        assertThat(auditCount("APP_POLICY_REQUESTED", "SUCCESS")).isPositive();
    }

    @Test
    void refusesPoliciesThatCouldCutOffTheServer() throws Exception {
        String rootApp = app("Everything", "LINUX_USER", "root");
        call(netAdmin, post("/api/v1/firewall-integrations"), Map.of("serverId", serverId)).andExpect(status().isCreated());
        report(List.of("LINUX_USER"), List.of(), false);
        call(analyst, post("/api/v1/application-policies/preview"), policy(rootApp, "BLOCK_INTERNET", List.of(), true))
                .andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.errors[0].code").value("APP_FORBIDDEN_IDENTITY"));
    }
}
