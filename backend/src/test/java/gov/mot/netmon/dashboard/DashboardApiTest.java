package gov.mot.netmon.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import gov.mot.netmon.support.IntegrationTest;
import gov.mot.netmon.user.RoleCode;

class DashboardApiTest extends IntegrationTest {

    @Test
    void summaryCountsInventoryAndReportsMetricsAsNotCollected() throws Exception {
        String admin = tokenFor("sa-user", RoleCode.SUPER_ADMIN);
        mvc.perform(post("/api/v1/servers").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "s1", "hostname", "s1.local", "osFamily", "WINDOWS",
                        "environment", "PRODUCTION", "organization", "Org A")))).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalServers").value(1))
                .andExpect(jsonPath("$.connection.NO_AGENT").value(1))
                .andExpect(jsonPath("$.connection.ONLINE").value(0))
                .andExpect(jsonPath("$.byOsFamily.WINDOWS").value(1))
                .andExpect(jsonPath("$.modules[0].availability").value("NOT_COLLECTED"))
                .andExpect(jsonPath("$.recentSecurityEvents").isArray());
    }

    @Test
    void readOnlyUserDoesNotReceiveSecurityEvents() throws Exception {
        mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", tokenFor("ro-user", RoleCode.READ_ONLY)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recentSecurityEvents").doesNotExist());
    }
}
