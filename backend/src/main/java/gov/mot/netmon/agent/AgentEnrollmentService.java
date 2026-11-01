package gov.mot.netmon.agent;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.agent.AgentDtos.EnrollmentTokenResponse;
import gov.mot.netmon.agent.AgentProtocol.EnrollRequest;
import gov.mot.netmon.agent.AgentProtocol.EnrollResponse;
import gov.mot.netmon.agent.AgentProtocol.RotateResponse;
import gov.mot.netmon.audit.AuditAction;
import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.auth.TokenHashing;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.common.ClientInfo;
import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.security.CurrentUser;
import gov.mot.netmon.security.RateLimiter;
import gov.mot.netmon.server.Server;
import gov.mot.netmon.server.ServerEnums.MonitoringStatus;
import gov.mot.netmon.server.ServerRepository;

/** Enrollment tokens, agent enrollment and credential rotation. */
@Service
public class AgentEnrollmentService {

    private final AgentRepository agents;
    private final EnrollmentTokenRepository tokens;
    private final ServerAgentConfigRepository configs;
    private final ServerRepository servers;
    private final AuditService audit;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final AppProperties.Agents props;

    public AgentEnrollmentService(AgentRepository agents, EnrollmentTokenRepository tokens,
            ServerAgentConfigRepository configs, ServerRepository servers, AuditService audit,
            RateLimiter loginRateLimiter, Clock clock, AppProperties appProperties) {
        this.agents = agents;
        this.tokens = tokens;
        this.configs = configs;
        this.servers = servers;
        this.audit = audit;
        this.rateLimiter = loginRateLimiter;
        this.clock = clock;
        this.props = appProperties.agents();
    }

    /** Issues a one-time token for a server; any earlier unused token for that server stops working. */
    @Transactional
    public EnrollmentTokenResponse createToken(UUID serverId) {
        Server server = servers.findById(serverId).orElseThrow(() -> ApiException.notFound("Server"));
        if (server.getMonitoringStatus() == MonitoringStatus.DECOMMISSIONED) {
            throw ApiException.conflict("SERVER_DECOMMISSIONED", "Decommissioned servers cannot enroll an agent");
        }
        Instant now = clock.instant();
        tokens.invalidateUnused(serverId, now);
        String raw = TokenHashing.newToken();
        EnrollmentToken token = tokens.save(new EnrollmentToken(serverId, TokenHashing.sha256(raw),
                now.plus(props.enrollmentTokenTtl()), CurrentUser.usernameOrNull()));
        audit.event(AuditAction.AGENT_ENROLLMENT_TOKEN_CREATED).target("SERVER", serverId)
                .detail("server", server.getName()).detail("expiresAt", token.getExpiresAt().toString()).success();
        String url = props.publicUrl() == null || props.publicUrl().isBlank() ? null : props.publicUrl().trim();
        return new EnrollmentTokenResponse(raw, token.getExpiresAt(), serverId, server.getName(), url);
    }

    /** Called by the agent (public endpoint). Exchanges a one-time token for a permanent agent secret. */
    @Transactional(noRollbackFor = ApiException.class)
    public EnrollResponse enroll(EnrollRequest req) {
        String ip = ClientInfo.currentIp();
        if (!rateLimiter.tryAcquire("enroll:" + ip)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many enrollment attempts");
        }
        Instant now = clock.instant();
        EnrollmentToken token = tokens.findByTokenHash(TokenHashing.sha256(req.token())).orElse(null);
        if (token == null || !token.isUsable(now)) {
            audit.event(AuditAction.AGENT_ENROLLMENT_FAILED).actor(null, "agent@" + req.hostname())
                    .reason(token == null ? "unknown token" : "token expired or already used").failure();
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ENROLLMENT_TOKEN",
                    "Enrollment token is invalid, expired or already used. Create a new one in the web UI.");
        }
        Server server = servers.findById(token.getServerId()).orElseThrow(() -> ApiException.notFound("Server"));
        token.markUsed(now);

        Agent agent = agents.findByServerId(server.getId()).orElseGet(() -> new Agent(server.getId()));
        boolean reEnroll = agent.getStatus() != null;
        String secret = TokenHashing.newToken();
        agent.enroll(TokenHashing.sha256(secret), req, ip, now);
        agents.save(agent);
        ServerAgentConfig config = configs.findById(server.getId())
                .orElseGet(() -> configs.save(new ServerAgentConfig(server.getId(), props.defaultReportInterval())));

        boolean mismatch = hostnameMismatch(server.getHostname(), req.hostname());
        audit.event(AuditAction.AGENT_ENROLLED).actor(null, "agent@" + req.hostname()).target("SERVER", server.getId())
                .detail("server", server.getName()).detail("agentId", agent.getId().toString())
                .detail("reEnrollment", reEnroll).detail("reportedHostname", req.hostname())
                .detail("hostnameMismatch", mismatch).detail("agentVersion", req.agentVersion()).success();
        return new EnrollResponse(agent.getId(), secret, server.getId(), server.getName(),
                AgentReportService.toProtocolConfig(config, server));
    }

    /** Called by an authenticated agent. The previous secret keeps working for a grace period. */
    @Transactional
    public RotateResponse rotate(AgentPrincipal principal) {
        Agent agent = agents.findById(principal.agentId()).orElseThrow(() -> ApiException.notFound("Agent"));
        Instant now = clock.instant();
        String secret = TokenHashing.newToken();
        Instant graceUntil = now.plus(props.credentialGracePeriod());
        agent.rotate(TokenHashing.sha256(secret), now, graceUntil);
        String serverName = servers.findById(agent.getServerId()).map(Server::getName).orElse("?");
        audit.event(AuditAction.AGENT_CREDENTIALS_ROTATED).actor(null, "agent@" + agent.getReportedHostname())
                .target("SERVER", agent.getServerId()).detail("server", serverName).success();
        return new RotateResponse(secret, graceUntil);
    }

    /** True when the agent's hostname clearly differs from the registered one (compares the first DNS label). */
    static boolean hostnameMismatch(String registered, String reported) {
        if (registered == null || reported == null) {
            return false;
        }
        String a = registered.toLowerCase(Locale.ROOT).split("\\.")[0];
        String b = reported.toLowerCase(Locale.ROOT).split("\\.")[0];
        return !a.equals(b) && !registered.equalsIgnoreCase(reported);
    }
}
