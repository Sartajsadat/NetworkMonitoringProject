package gov.mot.netmon.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only audit record. {@link Immutable} plus a DB trigger prevent modification. */
@Entity
@Immutable
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_username")
    private String actorUsername;

    @Column(name = "actor_roles")
    private String actorRoles;

    @Column(nullable = false)
    private String action;

    @Column(name = "target_type")
    private String targetType;

    @Column(name = "target_id")
    private String targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditResult result;

    @Column(name = "source_ip")
    private String sourceIp;

    @Column(name = "request_id")
    private String requestId;

    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> details;

    protected AuditLog() {
    }

    public AuditLog(Instant occurredAt, UUID actorUserId, String actorUsername, String actorRoles, String action,
            String targetType, String targetId, AuditResult result, String sourceIp, String requestId, String reason,
            Map<String, Object> details) {
        this.occurredAt = occurredAt;
        this.actorUserId = actorUserId;
        this.actorUsername = actorUsername;
        this.actorRoles = actorRoles;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.result = result;
        this.sourceIp = sourceIp;
        this.requestId = requestId;
        this.reason = reason;
        this.details = details;
    }

    public Long getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public UUID getActorUserId() { return actorUserId; }
    public String getActorUsername() { return actorUsername; }
    public String getActorRoles() { return actorRoles; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public AuditResult getResult() { return result; }
    public String getSourceIp() { return sourceIp; }
    public String getRequestId() { return requestId; }
    public String getReason() { return reason; }
    public Map<String, Object> getDetails() { return details; }
}
