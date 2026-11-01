package gov.mot.netmon.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import gov.mot.netmon.support.IntegrationTest;
import gov.mot.netmon.user.RoleCode;

class AuthApiTest extends IntegrationTest {

    @Test
    void loginReturnsAccessTokenAndHttpOnlyRefreshCookie() throws Exception {
        createUser("alice", RoleCode.READ_ONLY);
        MvcResult res = login("Alice", PASSWORD); // usernames are case-insensitive
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(res).get("accessToken").asText()).isNotBlank();
        assertThat(body(res).at("/user/username").asText()).isEqualTo("alice");
        assertThat(body(res).at("/user/roles/0").asText()).isEqualTo("READ_ONLY");

        Cookie cookie = res.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
        assertThat(res.getResponse().getHeader("Set-Cookie")).contains("SameSite=Strict");
        assertThat(auditCount("AUTH_LOGIN", "SUCCESS")).isPositive();
    }

    @Test
    void wrongPasswordAndUnknownUserGiveSameGenericError() throws Exception {
        createUser("bob", RoleCode.READ_ONLY);
        MvcResult wrong = login("bob", "Wrong-Password-1!");
        MvcResult unknown = login("nobody", "Wrong-Password-1!");
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(wrong).get("message").asText()).isEqualTo(body(unknown).get("message").asText());
        assertThat(auditCount("AUTH_LOGIN_FAILED", "FAILURE")).isGreaterThanOrEqualTo(2);
    }

    @Test
    void accountLocksAfterRepeatedFailures() throws Exception {
        createUser("carol", RoleCode.READ_ONLY);
        for (int i = 0; i < 5; i++) {
            assertThat(login("carol", "Wrong-Password-1!").getResponse().getStatus()).isEqualTo(401);
        }
        // Correct password is now refused because the account is locked.
        assertThat(login("carol", PASSWORD).getResponse().getStatus()).isEqualTo(401);
        Boolean locked = jdbc.queryForObject("SELECT locked_until > now() FROM users WHERE username='carol'",
                Boolean.class);
        assertThat(locked).isTrue();
    }

    @Test
    void protectedEndpointRequiresToken() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/servers").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsCurrentUser() throws Exception {
        String token = tokenFor("dave", RoleCode.NETWORK_ADMIN, RoleCode.READ_ONLY);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("dave"))
                .andExpect(jsonPath("$.roles.length()").value(2));
    }

    @Test
    void refreshRotatesTokenAndReuseRevokesAllSessions() throws Exception {
        createUser("erin", RoleCode.READ_ONLY);
        Cookie first = login("erin", PASSWORD).getResponse().getCookie(AuthController.REFRESH_COOKIE);

        MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh").cookie(first)
                .header("X-Requested-With", "netmon")).andExpect(status().isOk()).andReturn();
        Cookie second = refreshed.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        assertThat(second.getValue()).isNotEqualTo(first.getValue());
        String newAccess = "Bearer " + body(refreshed).get("accessToken").asText();

        // Replaying the rotated cookie is treated as theft.
        mvc.perform(post("/api/v1/auth/refresh").cookie(first).header("X-Requested-With", "netmon"))
                .andExpect(status().isUnauthorized());
        assertThat(auditCount("AUTH_TOKEN_REFRESH_REUSE_DETECTED", "FAILURE")).isPositive();

        // ...which kills the newer refresh token and the newer access token too.
        mvc.perform(post("/api/v1/auth/refresh").cookie(second).header("X-Requested-With", "netmon"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", newAccess)).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRequiresCsrfHeader() throws Exception {
        createUser("frank", RoleCode.READ_ONLY);
        Cookie c = login("frank", PASSWORD).getResponse().getCookie(AuthController.REFRESH_COOKIE);
        mvc.perform(post("/api/v1/auth/refresh").cookie(c)).andExpect(status().isForbidden());
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        createUser("gina", RoleCode.READ_ONLY);
        Cookie c = login("gina", PASSWORD).getResponse().getCookie(AuthController.REFRESH_COOKIE);
        mvc.perform(post("/api/v1/auth/logout").cookie(c).header("X-Requested-With", "netmon"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh").cookie(c).header("X-Requested-With", "netmon"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deactivatedUserTokenStopsWorkingImmediately() throws Exception {
        String admin = tokenFor("root1", RoleCode.SUPER_ADMIN);
        String userToken = tokenFor("henry", RoleCode.READ_ONLY);
        String henryId = jdbc.queryForObject("SELECT id::text FROM users WHERE username='henry'", String.class);

        mvc.perform(get("/api/v1/servers").header("Authorization", userToken)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/users/" + henryId + "/deactivate").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/v1/servers").header("Authorization", userToken)).andExpect(status().isUnauthorized());
        assertThat(login("henry", PASSWORD).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void changePasswordEnforcesPolicyAndRevokesSessions() throws Exception {
        String token = tokenFor("ivy", RoleCode.READ_ONLY);
        mvc.perform(post("/api/v1/auth/password").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("currentPassword", PASSWORD, "newPassword", "short"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
        mvc.perform(post("/api/v1/auth/password").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("currentPassword", PASSWORD, "newPassword", "Another-Strong-Pass-9"))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", token)).andExpect(status().isUnauthorized());
        assertThat(login("ivy", "Another-Strong-Pass-9").getResponse().getStatus()).isEqualTo(200);
    }
}
