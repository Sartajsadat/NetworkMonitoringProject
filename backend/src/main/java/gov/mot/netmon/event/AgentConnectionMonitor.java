package gov.mot.netmon.event;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.agent.Agent;
import gov.mot.netmon.agent.AgentRepository;
import gov.mot.netmon.agent.AgentStatus;
import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.server.Server;
import gov.mot.netmon.server.ServerRepository;

/**
 * Records when an agent stops reporting (once) and when it comes back, linked by one correlation
 * id. The disconnection is dated at the agent's last report, which is when the silence began.
 */
@Component
public class AgentConnectionMonitor {

    private final AgentRepository agents;
    private final ServerRepository servers;
    private final EventService events;
    private final Clock clock;
    private final Duration threshold;

    public AgentConnectionMonitor(AgentRepository agents, ServerRepository servers, EventService events, Clock clock,
            AppProperties props) {
        this.agents = agents;
        this.servers = servers;
        this.events = events;
        this.clock = clock;
        this.threshold = props.monitoring().onlineThreshold();
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    @Transactional
    public void detectDisconnections() {
        Instant now = clock.instant();
        for (Agent a : agents.findByStatus(AgentStatus.ACTIVE)) {
            if (a.getDisconnectedAt() != null || a.getLastSeenAt() == null) continue;
            if (!a.getLastSeenAt().isBefore(now.minus(threshold))) continue;
            Server s = servers.findById(a.getServerId()).orElse(null);
            if (s == null || !s.acceptsMetrics()) continue; // paused/decommissioned servers are expected to be quiet
            UUID correlation = UUID.randomUUID();
            a.markDisconnected(now, correlation);
            events.event(EventCategory.AVAILABILITY, "AGENT_DISCONNECTED", EventSeverity.ERROR)
                    .server(s.getId(), s.getName()).at(a.getLastSeenAt()).correlation(correlation)
                    .summary("Agent stopped reporting (no report for more than " + threshold.toMinutes() + " min)")
                    .detail("lastSeenAt", a.getLastSeenAt()).detail("thresholdSeconds", threshold.toSeconds()).record();
        }
    }

    /** Called by report ingestion (inside its transaction) before the agent's last-seen time is updated. */
    public void onReport(Agent a, Server s, Instant now) {
        if (a.getDisconnectedAt() == null) return;
        Instant lastSeen = a.getLastSeenAt();
        long offline = lastSeen == null ? 0 : Duration.between(lastSeen, now).toSeconds();
        events.event(EventCategory.AVAILABILITY, "AGENT_RECONNECTED", EventSeverity.INFO)
                .server(s.getId(), s.getName()).at(now).correlation(a.getDisconnectCorrelation())
                .summary("Agent reports again after about " + Math.max(1, offline / 60) + " min without contact")
                .detail("offlineSeconds", offline).detail("lastSeenAt", lastSeen).record();
        a.clearDisconnected();
    }
}
