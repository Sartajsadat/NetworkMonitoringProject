package gov.mot.netmon.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import gov.mot.netmon.support.AgentTestSupport;
import gov.mot.netmon.user.RoleCode;

/**
 * Production-hardening checks over the WHOLE API, discovered from the controllers, so a new
 * endpoint that forgets authentication or authorisation fails this test.
 */
class SecurityHardeningTest extends AgentTestSupport {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    /** Endpoints that are public on purpose. */
    static final Set<String> PUBLIC = Set.of("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/setup/status",
            "/api/v1/setup/admin", "/api/v1/agent/enroll", "/api/v1/captures/download");
    /** Writes a read-only user may legitimately make (their own session and password). */
    static final Set<String> READ_ONLY_WRITES = Set.of("POST /api/v1/auth/password", "POST /api/v1/auth/logout",
            "POST /api/v1/auth/refresh", "POST /api/v1/auth/login");

    record Endpoint(String method, String path) {
        String concrete() {
            return path.replaceAll("\\{[^}]+}", UUID.randomUUID().toString().replace("-", "").substring(0, 8).matches("\\d+") ? "1"
                    : UUID.randomUUID().toString());
        }
    }

    List<Endpoint> endpoints() {
        List<Endpoint> out = new ArrayList<>();
        mappings.getHandlerMethods().forEach((info, method) -> {
            for (String p : info.getPatternValues()) {
                if (!p.startsWith("/api/")) continue;
                Set<RequestMethod> ms = info.getMethodsCondition().getMethods();
                for (RequestMethod m : ms.isEmpty() ? Set.of(RequestMethod.GET) : ms) out.add(new Endpoint(m.name(), p));
            }
        });
        return out;
    }

    MvcResult call(Endpoint e, String auth) throws Exception {
        var req = request(HttpMethod.valueOf(e.method()), e.concrete().replaceAll("/segments/[^/]+$", "/segments/1"))
                .contentType(MediaType.APPLICATION_JSON).content("{}");
        if (auth != null) req.header("Authorization", auth);
        return mvc.perform(req).andReturn();
    }

    @Test
    void everyNonPublicEndpointRequiresAuthentication() throws Exception {
        List<Endpoint> all = endpoints();
        assertThat(all.size()).isGreaterThan(100);
        List<String> open = new ArrayList<>();
        for (Endpoint e : all) {
            if (PUBLIC.contains(e.path())) continue;
            int s = call(e, null).getResponse().getStatus();
            if (s != 401) open.add(e.method() + " " + e.path() + " -> " + s);
        }
        assertThat(open).as("endpoints reachable without signing in").isEmpty();
    }

    @Test
    void readOnlyUsersCannotChangeAnything() throws Exception {
        String readOnly = tokenFor("viewer", RoleCode.READ_ONLY);
        List<String> allowed = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            if (e.method().equals("GET") || e.path().startsWith("/api/v1/agent/") || PUBLIC.contains(e.path())) continue;
            int s = call(e, readOnly).getResponse().getStatus();
            if (s < 400 && !READ_ONLY_WRITES.contains(e.method() + " " + e.path())) allowed.add(e.method() + " " + e.path() + " -> " + s);
            assertThat(s).as(e.method() + " " + e.path()).isLessThan(500);
        }
        assertThat(allowed).as("writes a read-only user could make").isEmpty();
    }

    @Test
    void userAndAgentCredentialsAreNotInterchangeable() throws Exception {
        String admin = tokenFor("root", RoleCode.SUPER_ADMIN);
        String serverId = createServer(admin, "sec-01");
        String agent = enroll(admin, serverId, "sec-01.mot.local");
        mvc.perform(post("/api/v1/agent/report").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(report(java.time.Instant.now(), 1.0, 1.0)))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/servers").header("Authorization", agent)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/servers").header("Authorization", "Bearer " + agent.substring(6))).andExpect(status().isUnauthorized());
    }

    @Test
    void responsesCarrySecurityHeadersAndLeakNoInternals() throws Exception {
        String admin = tokenFor("root", RoleCode.SUPER_ADMIN);
        mvc.perform(get("/api/v1/servers").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        // Malformed input gives a clean 4xx, never a stack trace.
        for (var r : List.of(
                post("/api/v1/servers").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content("{not json"),
                get("/api/v1/servers/not-a-uuid").header("Authorization", admin),
                get("/api/v1/servers").param("search", "' OR 1=1; DROP TABLE servers; --").header("Authorization", admin),
                get("/api/v1/firewall-rules").param("address", "%' OR '1'='1").header("Authorization", admin),
                get("/api/v1/network-flows").param("ip", "1.2.3.4'--").header("Authorization", admin))) {
            MvcResult res = mvc.perform(r).andReturn();
            String body = res.getResponse().getContentAsString();
            assertThat(res.getResponse().getStatus()).isLessThan(500);
            assertThat(body).doesNotContain("Exception").doesNotContain("at gov.").doesNotContain("SQL");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM servers", Long.class)).isZero();
        // Only health and info are exposed from the actuator.
        for (String p : List.of("/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/loggers")) {
            assertThat(mvc.perform(get(p).header("Authorization", admin)).andReturn().getResponse().getStatus()).as(p).isIn(401, 403, 404);
        }
    }

    @Test
    void crossOriginRequestsFromUnknownOriginsAreRefused() throws Exception {
        mvc.perform(options("/api/v1/servers").header("Origin", "https://evil.example").header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
