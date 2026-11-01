package gov.mot.netmon.agent;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One-time token that lets an agent enroll for one specific server. Stored as SHA-256 only. */
@Entity
@Table(name = "agent_enrollment_tokens")
public class EnrollmentToken {

    @Id
    private UUID id;

    @Column(name = "server_id", nullable = false)
    private UUID serverId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private String createdBy;

    protected EnrollmentToken() {
    }

    public EnrollmentToken(UUID serverId, String tokenHash, Instant expiresAt, String createdBy) {
        this.id = UUID.randomUUID();
        this.serverId = serverId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
        this.createdBy = createdBy;
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public void markUsed(Instant now) {
        this.usedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getServerId() { return serverId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
}
