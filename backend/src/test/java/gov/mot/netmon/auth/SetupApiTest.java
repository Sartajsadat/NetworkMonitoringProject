package gov.mot.netmon.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import gov.mot.netmon.support.IntegrationTest;

class SetupApiTest extends IntegrationTest {

    private static final String TOKEN = "test-setup-token-0123456789abcdef";

    @Autowired
    SetupService setupService;

    @BeforeEach
    void armSetup() {
        setupService.prepareSetupToken(); // DB was emptied by the base class
    }

    private Map<String, String> request(String token) {
        Map<String, String> m = new HashMap<>();
        m.put("setupToken", token);
        m.put("username", "admin");
        m.put("email", "admin@example.gov");
        m.put("fullName", "First Admin");
        m.put("password", "Very-Strong-Initial-Pass1");
        return m;
    }

    @Test
    void firstAdminCanOnlyBeCreatedOnceWithValidToken() throws Exception {
        mvc.perform(get("/api/v1/setup/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.setupRequired").value(true));

        mvc.perform(post("/api/v1/setup/admin").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(request("wrong-token-wrong-token-wrong")))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_SETUP_TOKEN"));

        mvc.perform(post("/api/v1/setup/admin").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(request(TOKEN)))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles[0]").value("SUPER_ADMIN"));

        mvc.perform(get("/api/v1/setup/status")).andExpect(jsonPath("$.setupRequired").value(false));
        mvc.perform(post("/api/v1/setup/admin").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(request(TOKEN)))).andExpect(status().isConflict());

        assertThat(login("admin", "Very-Strong-Initial-Pass1").getResponse().getStatus()).isEqualTo(200);
        assertThat(auditCount("SETUP_ADMIN_CREATED", "SUCCESS")).isPositive();
    }

    @Test
    void setupRejectsWeakPassword() throws Exception {
        Map<String, String> req = request(TOKEN);
        req.put("password", "password");
        mvc.perform(post("/api/v1/setup/admin").contentType(MediaType.APPLICATION_JSON).content(toJson(req)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
    }
}
