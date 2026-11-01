package gov.mot.netmon.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import gov.mot.netmon.common.ClientInfo;
import gov.mot.netmon.common.RequestIdFilter;
import gov.mot.netmon.security.AuthenticatedUser;

/**
 * Writes audit records in their own transaction ({@code REQUIRES_NEW}) so that a failed
 * business operation is still audited even when its own transaction rolls back.
 * Callers must never pass passwords, tokens or packet contents in {@code details}.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditWriter writer;

    AuditService(AuditWriter writer) {
        this.writer = writer;
    }

    /** Fluent entry point: {@code audit.event(AuditAction.X).target("SERVER", id).success()}. */
    public Builder event(AuditAction action) {
        return new Builder(action);
    }

    public void recordAccessDenied(String method, String path) {
        event(AuditAction.ACCESS_DENIED).detail("method", method).detail("path", path).denied();
    }

    public final class Builder {
        private final AuditAction action;
        private String targetType;
        private String targetId;
        private String reason;
        private UUID actorId;
        private String actorUsername;
        private String actorRoles;
        private final Map<String, Object> details = new LinkedHashMap<>();

        private Builder(AuditAction action) {
            this.action = action;
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
                this.actorId = user.id();
                this.actorUsername = user.username();
                this.actorRoles = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                        .map(a -> a.replaceFirst("^ROLE_", "")).sorted().collect(Collectors.joining(","));
            }
        }

        /** Use when there is no authenticated principal yet (e.g. login attempts). */
        public Builder actor(UUID id, String username) {
            this.actorId = id;
            this.actorUsername = username;
            return this;
        }

        public Builder target(String type, Object id) {
            this.targetType = type;
            this.targetId = id == null ? null : id.toString();
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500);
            return this;
        }

        public Builder detail(String key, Object value) {
            if (value != null) {
                details.put(key, value);
            }
            return this;
        }

        public void success() {
            save(AuditResult.SUCCESS);
        }

        public void failure() {
            save(AuditResult.FAILURE);
        }

        public void denied() {
            save(AuditResult.DENIED);
        }

        private void save(AuditResult result) {
            AuditLog entry = new AuditLog(Instant.now(), actorId, actorUsername, actorRoles, action.name(),
                    targetType, targetId, result, ClientInfo.currentIp(), RequestIdFilter.current(), reason,
                    details.isEmpty() ? null : Map.copyOf(details));
            try {
                writer.write(entry);
            } catch (RuntimeException e) {
                // Losing an audit record is serious: log loudly with enough data to reconstruct it.
                log.error("AUDIT WRITE FAILED action={} actor={} target={}:{} result={}", action, actorUsername,
                        targetType, targetId, result, e);
                throw e;
            }
        }
    }
}
