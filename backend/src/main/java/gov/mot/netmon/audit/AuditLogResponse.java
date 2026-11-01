package gov.mot.netmon.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditLogResponse(
        Long id,
        Instant occurredAt,
        UUID actorUserId,
        String actorUsername,
        String actorRoles,
        String action,
        String targetType,
        String targetId,
        AuditResult result,
        String sourceIp,
        String requestId,
        String reason,
        Map<String, Object> details) {

    public static AuditLogResponse from(AuditLog a) {
        return new AuditLogResponse(a.getId(), a.getOccurredAt(), a.getActorUserId(), a.getActorUsername(),
                a.getActorRoles(), a.getAction(), a.getTargetType(), a.getTargetId(), a.getResult(),
                a.getSourceIp(), a.getRequestId(), a.getReason(), a.getDetails());
    }
}
