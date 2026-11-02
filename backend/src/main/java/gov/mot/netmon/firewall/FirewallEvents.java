package gov.mot.netmon.firewall;

import java.util.UUID;

import org.springframework.stereotype.Component;

import gov.mot.netmon.event.EventCategory;
import gov.mot.netmon.event.EventService;
import gov.mot.netmon.event.EventSeverity;
import gov.mot.netmon.server.ServerRepository;

/** Firewall rule lifecycle on the event timeline. The rule id is the correlation id. */
@Component
public class FirewallEvents {

    private final EventService events;
    private final ServerRepository servers;

    public FirewallEvents(EventService events, ServerRepository servers) {
        this.events = events;
        this.servers = servers;
    }

    static String describe(FirewallRule r) {
        StringBuilder b = new StringBuilder(r.getAction().equals("BLOCK") ? "block " : "allow ");
        b.append(r.getDirection().equals("INBOUND") ? "from " : "to ").append(r.getRemoteCidr());
        if (!"ANY".equals(r.getProtocol())) b.append(' ').append(r.getProtocol());
        if (r.getLocalPort() != null) b.append(" port ").append(r.getLocalPort());
        if (r.getRemotePort() != null) b.append(" remote port ").append(r.getRemotePort());
        return b.toString();
    }

    EventService.Builder base(FirewallRule r, FirewallIntegration i, String type, EventSeverity sev) {
        String server = i.getServerId() == null ? null : servers.findById(i.getServerId()).map(s -> s.getName()).orElse(null);
        return events.event(EventCategory.FIREWALL, type, sev).server(i.getServerId(), server).resource(i.getName())
                .ip(r.getRemoteCidr().endsWith("/32") || r.getRemoteCidr().endsWith("/128")
                        ? r.getRemoteCidr().substring(0, r.getRemoteCidr().indexOf('/')) : null)
                .correlation(r.getId()).detail("ruleId", r.getId()).detail("tag", r.getTag()).detail("rule", describe(r))
                .detail("target", i.getName()).detail("mode", r.getModeAtApply());
    }

    void rule(FirewallRule r, FirewallIntegration i, String type, EventSeverity sev, String summary) {
        base(r, i, type, sev).summary(summary).detail("status", r.getStatus()).detail("message", r.getStatusMessage()).record();
    }

    /** A rule tagged by NetMon is on the server but the platform does not know it as active. */
    void unknownRule(UUID serverId, String serverName, String tag) {
        events.event(EventCategory.FIREWALL, "FIREWALL_UNKNOWN_RULE", EventSeverity.WARNING).fromAgent().server(serverId, serverName)
                .resource("netmon:" + tag).summary("A NetMon-tagged firewall rule (" + tag + ") is present on " + serverName
                        + " but is not an active rule on the platform")
                .detail("tag", tag).record();
    }
}
