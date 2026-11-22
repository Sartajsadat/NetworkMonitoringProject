package gov.mot.netmon.internet;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A website on one computer's list, switched on (blocked) or off (allowed). */
@Entity
@Table(name = "blocked_sites")
public class BlockedSite {

    @Id
    private UUID id;
    @Column(name = "server_id")
    private UUID serverId;
    private String domain;
    private boolean blocked;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Column(name = "updated_by")
    private String updatedBy;

    protected BlockedSite() {
    }

    public BlockedSite(UUID serverId, String domain, String by, Instant now) {
        this.id = UUID.randomUUID();
        this.serverId = serverId;
        this.domain = domain;
        this.blocked = true;
        this.createdAt = now;
        this.createdBy = by;
        this.updatedAt = now;
        this.updatedBy = by;
    }

    public void setBlocked(boolean blocked, String by, Instant now) {
        this.blocked = blocked;
        this.updatedAt = now;
        this.updatedBy = by;
    }

    public UUID getId() { return id; }
    public UUID getServerId() { return serverId; }
    public String getDomain() { return domain; }
    public boolean isBlocked() { return blocked; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
