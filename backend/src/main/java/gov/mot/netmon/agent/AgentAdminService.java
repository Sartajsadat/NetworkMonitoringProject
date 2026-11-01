package gov.mot.netmon.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.agent.AgentDtos.AgentView;
import gov.mot.netmon.agent.AgentDtos.ConfigRequest;
import gov.mot.netmon.agent.AgentDtos.ConfigView;
import gov.mot.netmon.audit.AuditAction;
import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.security.CurrentUser;
import gov.mot.netmon.server.Server;
import gov.mot.netmon.server.ServerRepository;

@Service
public class AgentAdminService {

    private final AgentRepository agents;
    private final EnrollmentTokenRepository tokens;
    private final ServerAgentConfigRepository configs;
    private final ServerRepository servers;
    private final AuditService audit;
    private final Clock clock;
    private final Duration onlineThreshold;
    private final int defaultInterval;

    public AgentAdminService(AgentRepository agents, EnrollmentTokenRepository tokens,
            ServerAgentConfigRepository configs, ServerRepository servers, AuditService audit, Clock clock,
            AppProperties props) {
        this.agents = agents;
        this.tokens = tokens;
        this.configs = configs;
        this.servers = servers;
        this.audit = audit;
        this.clock = clock;
        this.onlineThreshold = props.monitoring().onlineThreshold();
        this.defaultInterval = props.agents().defaultReportInterval();
    }

    @Transactional(readOnly = true)
    public AgentView forServer(UUID serverId) {
        Server server = servers.findById(serverId).orElseThrow(() -> ApiException.notFound("Server"));
        return view(server, agents.findByServerId(serverId).orElse(null), configs.findById(serverId).orElse(null),
                pendingTokenExpiry(serverId));
    }

    /** All servers with their agent state (including servers that have no agent yet). */
    @Transactional(readOnly = true)
    public List<AgentView> list() {
        Map<UUID, Agent> byServer = agents.findAll().stream().collect(Collectors.toMap(Agent::getServerId, Function.identity()));
        Map<UUID, ServerAgentConfig> cfg = configs.findAll().stream()
                .collect(Collectors.toMap(ServerAgentConfig::getServerId, Function.identity()));
        Instant now = clock.instant();
        Map<UUID, Instant> pending = tokens.findAll().stream().filter(t -> t.isUsable(now))
                .collect(Collectors.toMap(EnrollmentToken::getServerId, EnrollmentToken::getExpiresAt, (a, b) -> a.isAfter(b) ? a : b));
        return servers.findAll().stream()
                .sorted(Comparator.comparing(s -> s.getName().toLowerCase()))
                .map(s -> view(s, byServer.get(s.getId()), cfg.get(s.getId()), pending.get(s.getId())))
                .toList();
    }

    @Transactional
    public AgentView revoke(UUID serverId) {
        Server server = servers.findById(serverId).orElseThrow(() -> ApiException.notFound("Server"));
        Agent agent = agents.findByServerId(serverId).filter(Agent::isActive)
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_AGENT", "This server has no active agent"));
        agent.revoke(CurrentUser.usernameOrNull(), clock.instant());
        server.clearAgent();
        tokens.invalidateUnused(serverId, clock.instant());
        audit.event(AuditAction.AGENT_REVOKED).target("SERVER", serverId).detail("server", server.getName())
                .detail("agentId", agent.getId().toString()).success();
        return view(server, agent, configs.findById(serverId).orElse(null), null);
    }

    @Transactional
    public AgentView updateConfig(UUID serverId, ConfigRequest req) {
        Server server = servers.findById(serverId).orElseThrow(() -> ApiException.notFound("Server"));
        long maxInterval = Math.max(10, onlineThreshold.toSeconds() / 2);
        if (req.reportIntervalSeconds() > maxInterval) {
            throw ApiException.badRequest("INTERVAL_TOO_LONG", "Report interval must be at most " + maxInterval
                    + " seconds so the server is not shown offline between reports (AGENT_ONLINE_THRESHOLD).");
        }
        List<String> services = req.services().stream().map(String::trim).distinct().sorted().toList();
        ServerAgentConfig cfg = configs.findById(serverId).orElseGet(() -> new ServerAgentConfig(serverId, defaultInterval));
        ConfigView before = configView(cfg);
        cfg.update(req.reportIntervalSeconds(), services, CurrentUser.usernameOrNull());
        configs.save(cfg);
        audit.event(AuditAction.AGENT_CONFIG_UPDATED).target("SERVER", serverId).detail("server", server.getName())
                .detail("intervalBefore", before.reportIntervalSeconds()).detail("intervalAfter", req.reportIntervalSeconds())
                .detail("servicesBefore", before.services()).detail("servicesAfter", services).success();
        return forServer(serverId);
    }

    private Instant pendingTokenExpiry(UUID serverId) {
        Instant now = clock.instant();
        return tokens.findAll().stream().filter(t -> t.getServerId().equals(serverId) && t.isUsable(now))
                .map(EnrollmentToken::getExpiresAt).max(Comparator.naturalOrder()).orElse(null);
    }

    private ConfigView configView(ServerAgentConfig c) {
        return c == null ? new ConfigView(defaultInterval, List.of(), null, null)
                : new ConfigView(c.getReportIntervalSeconds(), List.copyOf(c.getMonitoredServices()), c.getUpdatedAt(), c.getUpdatedBy());
    }

    private AgentView view(Server s, Agent a, ServerAgentConfig cfg, Instant pendingToken) {
        var connection = s.connectionStatus(clock.instant(), onlineThreshold);
        if (a == null) {
            return new AgentView(s.getId(), s.getName(), s.getHostname(), s.getOsFamily(), null, "NOT_ENROLLED", connection,
                    null, null, null, null, null, null, null, false, null, null, null, null, 0, null, null, null,
                    pendingToken, configView(cfg));
        }
        return new AgentView(s.getId(), s.getName(), s.getHostname(), s.getOsFamily(), a.getId(), a.getStatus().name(),
                connection, a.getAgentVersion(), a.getPlatform(), a.getOsName(), a.getOsVersion(), a.getKernel(),
                a.getArchitecture(), a.getReportedHostname(),
                AgentEnrollmentService.hostnameMismatch(s.getHostname(), a.getReportedHostname()),
                a.getEnrolledAt(), a.getEnrolledFromIp(), a.getLastSeenAt(), a.getLastReportIp(), a.getLastErrorCount(),
                a.getCredentialRotatedAt(), a.getRevokedAt(), a.getRevokedBy(), pendingToken, configView(cfg));
    }
}
