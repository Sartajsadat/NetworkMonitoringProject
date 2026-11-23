package gov.mot.netmon.internet;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.support.AgentTestSupport;
import gov.mot.netmon.user.RoleCode;

@TestPropertySource(properties = "PERSONAL_MODE=true")
class InternetControlApiTest extends AgentTestSupport {

    String admin;
    String analyst;
    String serverId;
    String agent;
    Instant clock = Instant.now().minusSeconds(60);

    @BeforeEach
    void setUp() throws Exception {
        admin = tokenFor("owner", RoleCode.SUPER_ADMIN);
        analyst = tokenFor("ana", RoleCode.SECURITY_ANALYST);
        serverId = createServer(admin, "my-pc");
        agent = enroll(admin, serverId, "my-pc");
    }

    ResultActions call(String token, MockHttpServletRequestBuilder req, Object body) throws Exception {
        req.header("Authorization", token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(toJson(body));
        return mvc.perform(req);
    }

    /** One agent report; returns the platform's response (with the config it sends back). */
    JsonNode report(List<String> blockedOnComputer) throws Exception {
        clock = clock.plusSeconds(1).isAfter(Instant.now()) ? clock.plusSeconds(1) : Instant.now();
        Map<String, Object> r = report(clock, 5.0, 1.0);
        Map<String, Object> fw = new HashMap<>();
        fw.put("enabled", true);
        fw.put("enforcementAllowed", true);
        fw.put("backend", "NFTABLES");
        fw.put("message", "nftables");
        fw.put("protectedAddresses", List.of());
        fw.put("ruleTags", List.of());
        fw.put("applicationControl", List.of("LINUX_USER"));
        r.put("firewall", fw);
        if (blockedOnComputer != null) r.put("sites", Map.of("enabled", true, "message", "ok", "blocked", blockedOnComputer));
        return body(mvc.perform(post("/api/v1/agent/report").header("Authorization", agent).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(r))).andExpect(status().isOk()).andReturn());
    }

    @Test
    void websitesAreSentToTheComputerAndReadBack() throws Exception {
        String base = "/api/v1/internet-control/" + serverId;
        call(analyst, get(base), null).andExpect(status().isForbidden());
        call(admin, post(base + "/sites"), Map.of("site", "1.2.3.4")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SITE"));

        JsonNode v = body(call(admin, post(base + "/sites"), Map.of("site", "https://www.YouTube.com/watch?v=1"))
                .andExpect(status().isOk()).andReturn());
        assertThat(v.at("/sites/list/0/domain").asText()).isEqualTo("youtube.com");
        assertThat(v.at("/sites/list/0/state").asText()).isEqualTo("OFF_ON_COMPUTER"); // agent has not reported sites yet

        JsonNode resp = report(List.of());
        assertThat(resp.at("/config/blockedSites/0").asText()).isEqualTo("youtube.com");
        call(admin, get(base), null).andExpect(jsonPath("$.sites.list[0].state").value("BLOCKING"));
        report(List.of("youtube.com"));
        String siteId = body(call(admin, get(base), null).andExpect(jsonPath("$.sites.list[0].state").value("BLOCKED"))
                .andExpect(jsonPath("$.sites.enabledOnComputer").value(true)).andReturn()).at("/sites/list/0/id").asText();

        call(admin, put("/api/v1/internet-control/sites/" + siteId), Map.of("blocked", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sites.list[0].state").value("UNBLOCKING"));
        assertThat(report(List.of("youtube.com")).at("/config/blockedSites").size()).isZero();
        report(List.of());
        call(admin, get(base), null).andExpect(jsonPath("$.sites.list[0].state").value("ALLOWED"));
        call(admin, delete("/api/v1/internet-control/sites/" + siteId), null).andExpect(jsonPath("$.sites.list.length()").value(0));
        assertThat(auditCount("WEBSITE_BLOCKED", "SUCCESS")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void appSwitchAppliesAtOnceInPersonalMode() throws Exception {
        String base = "/api/v1/internet-control/" + serverId;
        report(null);
        JsonNode v = body(call(admin, post(base + "/apps"), Map.of("name", "Games", "identity", "gamer")).andExpect(status().isOk()).andReturn());
        assertThat(v.at("/apps/identityKind").asText()).isEqualTo("LINUX_USER");
        assertThat(v.at("/apps/enforcing").asBoolean()).isTrue();
        String appId = v.at("/apps/list/0/id").asText();

        // Switch internet off: no second person, no firewall target set up by hand.
        v = body(call(admin, put("/api/v1/internet-control/apps/" + appId), Map.of("internet", false)).andExpect(status().isOk()).andReturn());
        assertThat(v.at("/apps/list/0/internetBlocked").asBoolean()).isTrue();
        assertThat(v.at("/apps/list/0/status").asText()).isEqualTo("APPROVED");
        JsonNode task = body(mvc.perform(post("/api/v1/agent/tasks").header("Authorization", agent)).andReturn()).get("tasks").get(0)
                .get("firewall");
        assertThat(task.get("appMode").asText()).isEqualTo("BLOCK_INTERNET");
        assertThat(task.get("mode").asText()).isEqualTo("ENFORCE");

        // Switching it off twice does not create a second policy.
        call(admin, put("/api/v1/internet-control/apps/" + appId), Map.of("internet", false)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM firewall_rules", Long.class)).isEqualTo(1L);

        v = body(call(admin, put("/api/v1/internet-control/apps/" + appId), Map.of("internet", true)).andExpect(status().isOk()).andReturn());
        assertThat(v.at("/apps/list/0/internetBlocked").asBoolean()).isFalse();
        assertThat(v.at("/apps/list/0/status").asText()).isEqualTo("REMOVAL_PENDING");
    }
}
