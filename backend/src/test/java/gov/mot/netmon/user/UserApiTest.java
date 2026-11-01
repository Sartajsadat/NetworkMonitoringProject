package gov.mot.netmon.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import gov.mot.netmon.support.IntegrationTest;

class UserApiTest extends IntegrationTest {

    private String admin;

    @BeforeEach
    void signIn() throws Exception {
        admin = tokenFor("root", RoleCode.SUPER_ADMIN);
    }

    private String id(String username) {
        return jdbc.queryForObject("SELECT id::text FROM users WHERE username=?", String.class, username);
    }

    @Test
    void createsUserWithRolesAndNeverReturnsPasswordHash() throws Exception {
        String res = mvc.perform(post("/api/v1/users").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("username", "Net.Ops", "email", "NetOps@Example.gov", "fullName", "Net Ops",
                        "password", "Strong-Password-123", "roles", List.of("NETWORK_ADMIN")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("net.ops"))
                .andExpect(jsonPath("$.email").value("netops@example.gov"))
                .andExpect(jsonPath("$.roles[0]").value("NETWORK_ADMIN"))
                .andReturn().getResponse().getContentAsString();
        assertThat(res).doesNotContain("passwordHash").doesNotContain("$2a$");
    }

    @Test
    void rejectsWeakPasswordDuplicateAndUnknownRole() throws Exception {
        mvc.perform(post("/api/v1/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("username", "weak", "email", "w@example.gov", "fullName", "W",
                        "password", "alllowercaseletters", "roles", List.of("READ_ONLY")))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
        mvc.perform(post("/api/v1/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("username", "ROOT", "email", "x@example.gov", "fullName", "Dup",
                        "password", "Strong-Password-123", "roles", List.of("READ_ONLY")))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
        mvc.perform(post("/api/v1/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("username", "r2", "email", "r2@example.gov", "fullName", "R",
                        "password", "Strong-Password-123", "roles", List.of("GOD_MODE")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lastSuperAdminIsProtected() throws Exception {
        String rootId = id("root");
        mvc.perform(put("/api/v1/users/" + rootId).header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("email", "root@example.gov", "fullName", "Root", "roles", List.of("READ_ONLY")))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_SUPER_ADMIN"));
        mvc.perform(post("/api/v1/users/" + rootId + "/deactivate").header("Authorization", admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELF_DEACTIVATION"));
    }

    @Test
    void resetPasswordRevokesSessionsAndUnlocks() throws Exception {
        String userToken = tokenFor("jane", RoleCode.READ_ONLY);
        jdbc.update("UPDATE users SET locked_until = now() + interval '1 hour' WHERE username='jane'");
        mvc.perform(get("/api/v1/users/" + id("jane")).header("Authorization", admin))
                .andExpect(jsonPath("$.locked").value(true));
        mvc.perform(post("/api/v1/users/" + id("jane") + "/reset-password").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("newPassword", "Brand-New-Pass-77"))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", userToken)).andExpect(status().isUnauthorized());
        assertThat(login("jane", "Brand-New-Pass-77").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void searchAndRoleList() throws Exception {
        createUser("kate", RoleCode.READ_ONLY);
        mvc.perform(get("/api/v1/users?search=kat").header("Authorization", admin))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/roles").header("Authorization", admin))
                .andExpect(jsonPath("$.length()").value(5));
    }
}
