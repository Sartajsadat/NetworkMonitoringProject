package gov.mot.netmon.agent;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import gov.mot.netmon.auth.TokenHashing;
import gov.mot.netmon.common.GlobalExceptionHandler;

/**
 * Authenticates agents with {@code Authorization: Agent <agentId>:<secret>}. The secret is a
 * 256-bit random value; only its SHA-256 is stored, compared in constant time. TLS is required in
 * production so the header cannot be observed (mutual TLS is planned for phase 9).
 * <p>Not a Spring bean on purpose, so it is only part of the agent filter chain.
 */
public class AgentAuthenticationFilter extends OncePerRequestFilter {

    public static final String SCHEME = "Agent ";
    private static final Logger log = LoggerFactory.getLogger(AgentAuthenticationFilter.class);

    private final AgentRepository agents;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AgentAuthenticationFilter(AgentRepository agents, ObjectMapper mapper, Clock clock) {
        this.agents = agents;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith(SCHEME)) {
            chain.doFilter(req, res); // enroll is public; anything else is rejected by authorization
            return;
        }
        String credential = header.substring(SCHEME.length()).trim();
        int sep = credential.indexOf(':');
        UUID agentId;
        try {
            agentId = UUID.fromString(credential.substring(0, Math.max(sep, 0)));
        } catch (IllegalArgumentException e) {
            reject(res, req, "AGENT_UNAUTHORIZED", "Malformed agent credential");
            return;
        }
        String secret = credential.substring(sep + 1);
        Agent agent = agents.findById(agentId).orElse(null);
        if (agent == null || !matches(agent, secret, clock.instant())) {
            log.warn("Agent authentication failed for agent {} from {}", agentId, req.getRemoteAddr());
            reject(res, req, "AGENT_UNAUTHORIZED", "Agent credential is not valid");
            return;
        }
        if (!agent.isActive()) {
            reject(res, req, "AGENT_REVOKED", "This agent has been revoked. Re-enroll it with a new enrollment token.");
            return;
        }
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                new AgentPrincipal(agent.getId(), agent.getServerId()), null,
                List.of(new SimpleGrantedAuthority("ROLE_AGENT")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            chain.doFilter(req, res);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    static boolean matches(Agent agent, String secret, Instant now) {
        String hash = TokenHashing.sha256(secret);
        if (TokenHashing.constantTimeEquals(hash, agent.getSecretHash())) {
            return true;
        }
        return agent.getPreviousSecretHash() != null && agent.getPreviousSecretValidUntil() != null
                && agent.getPreviousSecretValidUntil().isAfter(now)
                && TokenHashing.constantTimeEquals(hash, agent.getPreviousSecretHash());
    }

    private void reject(HttpServletResponse res, HttpServletRequest req, String code, String message) throws IOException {
        res.setStatus(HttpStatus.UNAUTHORIZED.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(),
                GlobalExceptionHandler.body(HttpStatus.UNAUTHORIZED, code, message, req.getRequestURI(), List.of()));
    }
}
