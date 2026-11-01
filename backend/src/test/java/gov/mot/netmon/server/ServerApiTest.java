package gov.mot.netmon.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import gov.mot.netmon.support.IntegrationTest;
import gov.mot.netmon.user.RoleCode;

class ServerApiTest extends IntegrationTest {

    private String admin;

    @BeforeEach
    void signIn() throws Exception {
        admin = tokenFor("infra", RoleCode.INFRASTRUCTURE_ADMIN);
    }

    private Map<String, Object> server(String name, String env, Object ips) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("hostname", name + ".mot.local");
        m.put("osFamily", "LINUX");
        m.put("osVersion", "Ubuntu 24.04");
        m.put("environment", env);
        m.put("organization", "Ministry of Health");
        m.put("ipAddresses", ips);
        return m;
    }

    private String create(Map<String, Object> body) throws Exception {
        return body(mvc.perform(post("/api/v1/servers").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    @Test
    void registersServerWithNormalisedIpv4AndIpv6() throws Exception {
        String id = create(server("web-01", "PRODUCTION", List.of(
                Map.of("address", "10.20.0.5", "label", "mgmt"),
                Map.of("address", "2001:0DB8:0000:0000:0000:0000:0000:0001"))));
        mvc.perform(get("/api/v1/servers/" + id).header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ipAddresses[0].address").value("10.20.0.5"))
                .andExpect(jsonPath("$.ipAddresses[0].version").value(4))
                .andExpect(jsonPath("$.ipAddresses[1].address").value("2001:db8::1"))
                .andExpect(jsonPath("$.ipAddresses[1].version").value(6))
                .andExpect(jsonPath("$.monitoringStatus").value("PENDING_AGENT"))
                .andExpect(jsonPath("$.connectionStatus").value("NO_AGENT"))
                .andExpect(jsonPath("$.lastSeenAt").doesNotExist());
        assertThat(auditCount("SERVER_REGISTERED", "SUCCESS")).isPositive();
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        mvc.perform(post("/api/v1/servers").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(server("bad-ip", "TESTING", List.of(Map.of("address", "300.1.1.1"))))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/servers").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(server("dns-name", "TESTING", List.of(Map.of("address", "example.com"))))))
                .andExpect(status().isBadRequest());
        Map<String, Object> badHost = server("bad-host", "TESTING", List.of());
        badHost.put("hostname", "rm -rf /;");
        mvc.perform(post("/api/v1/servers").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(badHost))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HOSTNAME"));
        Map<String, Object> missing = new HashMap<>(Map.of("name", "x"));
        mvc.perform(post("/api/v1/servers").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(missing))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void rejectsDuplicateNameCaseInsensitively() throws Exception {
        create(server("db-01", "TESTING", List.of()));
        mvc.perform(post("/api/v1/servers").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(server("DB-01", "TESTING", List.of())))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVER_NAME_TAKEN"));
    }

    @Test
    void cannotManuallyMarkServerActive() throws Exception {
        Map<String, Object> s = server("app-01", "TESTING", List.of());
        s.put("monitoringStatus", "ACTIVE");
        mvc.perform(post("/api/v1/servers").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(s))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
    }

    @Test
    void filtersBySearchEnvironmentAndGroup() throws Exception {
        String groupId = body(mvc.perform(post("/api/v1/server-groups").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "Health portal", "groupType", "APPLICATION"))))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();

        Map<String, Object> a = server("alpha", "PRODUCTION", List.of(Map.of("address", "192.168.10.11")));
        a.put("groupIds", List.of(groupId));
        create(a);
        create(server("beta", "TESTING", List.of(Map.of("address", "192.168.20.12"))));

        mvc.perform(get("/api/v1/servers?environment=PRODUCTION").header("Authorization", admin))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].name").value("alpha"));
        mvc.perform(get("/api/v1/servers?search=192.168.20").header("Authorization", admin))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].name").value("beta"));
        mvc.perform(get("/api/v1/servers?groupId=" + groupId).header("Authorization", admin))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/server-groups").header("Authorization", admin))
                .andExpect(jsonPath("$[0].memberCount").value(1));
        mvc.perform(get("/api/v1/servers?sort=password").header("Authorization", admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SORT"));
        mvc.perform(get("/api/v1/servers?size=5000").header("Authorization", admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateKeepsUnchangedIpsAndAuditsDiff() throws Exception {
        String id = create(server("cache-01", "TESTING", List.of(Map.of("address", "10.0.0.1", "label", "a"))));
        Map<String, Object> upd = server("cache-01", "STAGING", List.of(
                Map.of("address", "10.0.0.1", "label", "renamed"), Map.of("address", "fe80::1")));
        mvc.perform(put("/api/v1/servers/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(upd))).andExpect(status().isOk())
                .andExpect(jsonPath("$.environment").value("STAGING"))
                .andExpect(jsonPath("$.ipAddresses.length()").value(2))
                .andExpect(jsonPath("$.ipAddresses[0].label").value("renamed"));
        String details = jdbc.queryForObject("SELECT details::text FROM audit_logs WHERE action='SERVER_UPDATED' "
                + "AND target_id=? ORDER BY id DESC LIMIT 1", String.class, id);
        assertThat(details).contains("environment").contains("TESTING").contains("STAGING");
    }

    @Test
    void unknownServerIs404() throws Exception {
        mvc.perform(get("/api/v1/servers/00000000-0000-0000-0000-000000000000").header("Authorization", admin))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.requestId").isNotEmpty());
    }
}
