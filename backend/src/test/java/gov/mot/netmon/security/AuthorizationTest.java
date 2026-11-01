package gov.mot.netmon.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import gov.mot.netmon.support.IntegrationTest;
import gov.mot.netmon.user.RoleCode;

/** Role matrix checks: the backend, not the UI, decides who may do what. */
class AuthorizationTest extends IntegrationTest {

    private static final Map<String, Object> SERVER = Map.of("name", "srv-a", "hostname", "srv-a.mot.local",
            "osFamily", "LINUX", "environment", "TESTING", "organization", "Ministry IT");

    @Test
    void readOnlyCanViewButNotModifyServers() throws Exception {
        String token = tokenFor("viewer", RoleCode.READ_ONLY);
        mvc.perform(get("/api/v1/servers").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/servers").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(SERVER))).andExpect(status().isForbidden());
        assertThat(auditCount("ACCESS_DENIED", "DENIED")).isPositive();
    }

    @Test
    void infrastructureAdminCanRegisterServers() throws Exception {
        String token = tokenFor("infra", RoleCode.INFRASTRUCTURE_ADMIN);
        mvc.perform(post("/api/v1/servers").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(SERVER))).andExpect(status().isCreated());
    }

    @Test
    void onlySuperAdminManagesUsers() throws Exception {
        for (RoleCode role : new RoleCode[] {RoleCode.INFRASTRUCTURE_ADMIN, RoleCode.NETWORK_ADMIN,
                RoleCode.SECURITY_ANALYST, RoleCode.READ_ONLY}) {
            String token = tokenFor("u-" + role.name().toLowerCase().replace('_', '-'), role);
            mvc.perform(get("/api/v1/users").header("Authorization", token)).andExpect(status().isForbidden());
        }
        String admin = tokenFor("boss", RoleCode.SUPER_ADMIN);
        mvc.perform(get("/api/v1/users").header("Authorization", admin)).andExpect(status().isOk());
    }

    @Test
    void auditLogVisibleToSuperAdminAndSecurityAnalystOnly() throws Exception {
        mvc.perform(get("/api/v1/audit-logs").header("Authorization", tokenFor("analyst", RoleCode.SECURITY_ANALYST)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/audit-logs").header("Authorization", tokenFor("netadm", RoleCode.NETWORK_ADMIN)))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteServerIsSuperAdminOnly() throws Exception {
        String infra = tokenFor("infra2", RoleCode.INFRASTRUCTURE_ADMIN);
        String id = body(mvc.perform(post("/api/v1/servers").header("Authorization", infra)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(SERVER))).andReturn()).get("id").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/servers/" + id)
                .header("Authorization", infra)).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/servers/" + id)
                .header("Authorization", tokenFor("sa-user", RoleCode.SUPER_ADMIN))).andExpect(status().isNoContent());
    }

    @Test
    void healthEndpointIsPublicButRevealsNoDetails() throws Exception {
        String body = mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(body).contains("UP").doesNotContain("db").doesNotContain("diskSpace");
    }
}
