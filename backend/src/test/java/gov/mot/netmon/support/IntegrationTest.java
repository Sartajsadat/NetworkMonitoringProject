package gov.mot.netmon.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import gov.mot.netmon.security.RateLimiter;
import gov.mot.netmon.user.RoleCode;
import gov.mot.netmon.user.UserService;

/**
 * Base class for API tests: one PostgreSQL container shared by all test classes (Flyway runs the
 * real migrations), MockMvc, and helpers to create users and sign in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    public static final String PASSWORD = "Correct-Horse-42!";

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected UserService userService;
    @Autowired
    protected RateLimiter loginRateLimiter;

    @Autowired
    protected gov.mot.netmon.capture.CaptureIndexer captureIndexer;

    @BeforeEach
    void cleanDatabase() {
        captureIndexer.awaitIdle(); // background indexing from the previous test must not race the cleanup
        // audit_logs is append-only by design and is intentionally not cleaned.
        jdbc.execute("TRUNCATE packet_summaries, capture_segments, agent_tasks, packet_captures, metric_rollups_5m, metric_samples, server_snapshots, server_agent_configs, "
                + "agent_enrollment_tokens, agents, server_group_members, server_ip_addresses, servers, server_groups, "
                + "refresh_tokens, user_roles, users, system_settings");
        loginRateLimiter.reset();
    }

    protected void createUser(String username, RoleCode... roles) {
        userService.createUser(username, username + "@example.gov", "Test " + username, PASSWORD, Set.of(roles));
    }

    protected MvcResult login(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn();
    }

    /** Creates a user with the given roles and returns a valid "Bearer ..." header value. */
    protected String tokenFor(String username, RoleCode... roles) throws Exception {
        createUser(username, roles);
        MvcResult res = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + body(res).get("accessToken").asText();
    }

    protected JsonNode body(MvcResult res) throws Exception {
        return json.readTree(res.getResponse().getContentAsString());
    }

    protected String toJson(Object o) throws Exception {
        return json.writeValueAsString(o);
    }

    protected long auditCount(String action, String result) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = ? AND result = ?", Long.class,
                action, result);
        return n == null ? 0 : n;
    }
}
