package gov.mot.netmon.event;

import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import gov.mot.netmon.audit.AuditLog;
import gov.mot.netmon.audit.AuditResult;
import gov.mot.netmon.audit.AuditService.AuditRecorded;
import gov.mot.netmon.server.ServerRepository;

/**
 * Mirrors security-relevant audit records into the event timeline, so failed sign-ins or a
 * revoked agent appear next to metrics and alerts. Attempted user names are only shown when they
 * belong to a real account (a mistyped password in the user-name field must not end up here).
 */
@Component
public class SecurityEventBridge {

    private final EventService events;
    private final ServerRepository servers;

    public SecurityEventBridge(EventService events, ServerRepository servers) {
        this.events = events;
        this.servers = servers;
    }

    @EventListener
    public void on(AuditRecorded recorded) {
        AuditLog a = recorded.entry();
        switch (a.getAction()) {
            case "AUTH_LOGIN_FAILED" -> loginFailed(a);
            case "AUTH_TOKEN_REFRESH_REUSE_DETECTED" -> security(a, "TOKEN_REUSE_DETECTED", EventSeverity.CRITICAL,
                    "A session token was reused; the session was revoked (possible token theft)", true);
            case "SETUP_ATTEMPT_FAILED" -> security(a, "SETUP_ATTEMPT_FAILED", EventSeverity.WARNING,
                    "Failed first-run setup attempt", false);
            case "AGENT_AUTH_FAILED" -> security(a, "AGENT_AUTH_FAILED", EventSeverity.WARNING,
                    "An agent connection was refused (invalid or revoked credential)", false);
            case "AGENT_REVOKED" -> agent(a, "AGENT_REVOKED", EventSeverity.WARNING, "Agent credential revoked by " + a.getActorUsername());
            case "AGENT_ENROLLED" -> agent(a, "AGENT_ENROLLED", EventSeverity.INFO, "Agent enrolled");
            default -> {
            }
        }
    }

    private void loginFailed(AuditLog a) {
        String reason = a.getReason() == null ? "" : a.getReason();
        boolean locked = reason.contains("now locked");
        boolean knownAccount = a.getActorUserId() != null;
        String type = locked ? "ACCOUNT_LOCKED" : reason.contains("rate limited") ? "LOGIN_RATE_LIMITED" : "LOGIN_FAILED";
        events.event(EventCategory.SECURITY, type, locked ? EventSeverity.ERROR : EventSeverity.WARNING)
                .at(a.getOccurredAt()).ip(a.getSourceIp())
                .summary(locked ? "Account " + a.getActorUsername() + " locked after repeated failed sign-ins"
                        : "Failed sign-in" + (knownAccount ? " for account " + a.getActorUsername() : " (unknown account)")
                                + (a.getSourceIp() == null ? "" : " from " + a.getSourceIp()))
                .detail("username", knownAccount ? a.getActorUsername() : null).detail("reason", reason)
                .detail("requestId", a.getRequestId()).recordNow();
    }

    private void security(AuditLog a, String type, EventSeverity severity, String summary, boolean withUser) {
        events.event(EventCategory.SECURITY, type, severity).at(a.getOccurredAt()).ip(a.getSourceIp()).summary(summary)
                .detail("username", withUser ? a.getActorUsername() : null).detail("reason", a.getReason())
                .detail("requestId", a.getRequestId()).recordNow();
    }

    private void agent(AuditLog a, String type, EventSeverity severity, String summary) {
        if (a.getResult() != AuditResult.SUCCESS || !"SERVER".equals(a.getTargetType())) return;
        UUID serverId = UUID.fromString(a.getTargetId());
        String name = servers.findById(serverId).map(s -> s.getName()).orElse(null);
        events.event(EventCategory.AGENT, type, severity).at(a.getOccurredAt()).server(serverId, name).summary(summary)
                .detail("by", a.getActorUsername()).recordNow();
    }
}
