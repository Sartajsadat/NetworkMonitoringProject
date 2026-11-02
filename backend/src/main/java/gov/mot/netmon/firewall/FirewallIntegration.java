package gov.mot.netmon.firewall;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "firewall_integrations")
public class FirewallIntegration {

    @Id
    private UUID id;
    private String name;
    private String kind;
    @Column(name = "server_id")
    private UUID serverId;
    private String mode;
    private String description;
    @Column(name = "mode_changed_by")
    private String modeChangedBy;
    @Column(name = "mode_changed_at")
    private Instant modeChangedAt;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected FirewallIntegration() {
    }

    public FirewallIntegration(String name, String kind, UUID serverId, String description, String by, Instant now) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.kind = kind;
        this.serverId = serverId;
        this.description = description;
        this.mode = "DRY_RUN";
        this.createdBy = by;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void setMode(String mode, String by, Instant now) {
        this.mode = mode;
        this.modeChangedBy = by;
        this.modeChangedAt = now;
        this.updatedAt = now;
    }

    public boolean isAgent() {
        return "SERVER_AGENT".equals(kind);
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getKind() { return kind; }
    public UUID getServerId() { return serverId; }
    public String getMode() { return mode; }
    public String getDescription() { return description; }
    public String getModeChangedBy() { return modeChangedBy; }
    public Instant getModeChangedAt() { return modeChangedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
}
