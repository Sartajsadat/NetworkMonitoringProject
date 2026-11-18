package gov.mot.netmon.website;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.support.AgentTestSupport;
import gov.mot.netmon.user.RoleCode;

class WebsiteApiTest extends AgentTestSupport {

    String admin;
    String viewer;
    String linux;
    String windows;
    String linuxAgent;
    String windowsAgent;
    Instant clock = Instant.now().minusSeconds(120);

    @BeforeEach
    void setUp() throws Exception {
        admin = tokenFor("neta", RoleCode.NETWORK_ADMIN);
        viewer = tokenFor("viewer", RoleCode.READ_ONLY);
        String root = tokenFor("root", RoleCode.SUPER_ADMIN);
        linux = createServer(root, "app-01");
        windows = createServer(root, "pc-finance-3");
        linuxAgent = enroll(root, linux, "app-01.mot.local");
        windowsAgent = enroll(root, windows, "pc-finance-3.mot.local");
    }

    ResultActions call(String token, MockHttpServletRequestBuilder req, Object body) throws Exception {
        req.header("Authorization", token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(toJson(body));
        return mvc.perform(req);
    }

    /** Sends a report and returns the blocked list the platform sends back in the settings. */
    JsonNode report(String agent, Map<String, Object> websites) throws Exception {
        clock = clock.plusSeconds(1);
        Map<String, Object> r = report(clock, 5.0, 1.0);
        if (websites != null) r.put("websites", websites);
        return body(mvc.perform(post("/api/v1/agent/report").header("Authorization", agent).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(r))).andExpect(status().isOk()).andReturn()).get("config").get("blockedSites");
    }

    JsonNode site(JsonNode overview, String domain) {
        for (JsonNode s : overview.get("sites")) if (s.get("domain").asText().equals(domain)) return s;
        throw new AssertionError(domain + " not listed");
    }

    @Test
    void switchFollowsWhatEachDeviceConfirms() throws Exception {
        // The audit log is append-only and never cleared between tests: count only what this test adds.
        long added = auditCount("WEBSITE_ADDED", "SUCCESS"), switched = auditCount("WEBSITE_SWITCHED", "SUCCESS"),
                deleted = auditCount("WEBSITE_DELETED", "SUCCESS");
        // An old agent (no "websites" in its report) and a device where blocking is not enabled.
        report(linuxAgent, null);
        report(windowsAgent, Map.of("enabled", false, "message", "Website blocking is not enabled on this device"));

        call(viewer, post("/api/v1/websites"), Map.of("website", "facebook.com")).andExpect(status().isForbidden());
        call(admin, post("/api/v1/websites"), Map.of("website", "10.0.0.1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WEBSITE"));
        JsonNode o = body(call(admin, post("/api/v1/websites"), Map.of("website", "https://www.Facebook.com/home"))
                .andExpect(status().isCreated()).andReturn());
        JsonNode fb = site(o, "facebook.com");
        assertThat(fb.get("blocked").asBoolean()).isTrue();
        assertThat(fb.get("summary").get("notSetUp").asInt()).isEqualTo(2);
        call(admin, post("/api/v1/websites"), Map.of("website", "facebook.com")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WEBSITE_EXISTS"));
        String id = fb.get("id").asText();

        // Both devices get the list; the Windows PC is set up and confirms it, the Linux server does not yet.
        assertThat(report(windowsAgent, Map.of("enabled", true, "blocked", List.of())).toString()).isEqualTo("[\"facebook.com\"]");
        report(linuxAgent, Map.of("enabled", true, "blocked", List.of()));
        fb = site(body(call(viewer, get("/api/v1/websites"), null).andReturn()), "facebook.com");
        assertThat(fb.get("summary").get("waiting").asInt()).isEqualTo(2);
        report(windowsAgent, Map.of("enabled", true, "blocked", List.of("facebook.com")));
        fb = site(body(call(viewer, get("/api/v1/websites"), null).andReturn()), "facebook.com");
        assertThat(fb.get("summary").get("done").asInt()).isEqualTo(1);
        assertThat(fb.get("summary").get("waiting").asInt()).isEqualTo(1);

        // Off: devices are sent an empty list and show Allowed once facebook.com is gone from their hosts file.
        call(admin, put("/api/v1/websites/" + id + "/blocked"), Map.of("blocked", false)).andExpect(status().isOk());
        assertThat(report(windowsAgent, Map.of("enabled", true, "blocked", List.of())).size()).isZero();
        fb = site(body(call(viewer, get("/api/v1/websites"), null).andReturn()), "facebook.com");
        assertThat(fb.get("blocked").asBoolean()).isFalse();
        assertThat(fb.get("devices").findValuesAsText("state")).contains("ALLOWED");

        // An older report never overwrites a newer one.
        clock = clock.minusSeconds(30);
        report(windowsAgent, Map.of("enabled", true, "blocked", List.of("facebook.com")));
        clock = clock.plusSeconds(60);
        fb = site(body(call(viewer, get("/api/v1/websites"), null).andReturn()), "facebook.com");
        assertThat(fb.get("devices").findValuesAsText("state")).contains("ALLOWED");

        call(admin, delete("/api/v1/websites/" + id), null).andExpect(status().isOk()).andExpect(jsonPath("$.sites.length()").value(0));
        assertThat(auditCount("WEBSITE_ADDED", "SUCCESS") - added).isEqualTo(1);
        assertThat(auditCount("WEBSITE_SWITCHED", "SUCCESS") - switched).isEqualTo(1);
        assertThat(auditCount("WEBSITE_DELETED", "SUCCESS") - deleted).isEqualTo(1);
    }
}
