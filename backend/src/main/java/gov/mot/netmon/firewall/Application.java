package gov.mot.netmon.firewall;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An application on one server, with the identity its operating system can enforce. */
@Entity
@Table(name = "application_inventory")
public class Application {

    @Id
    private UUID id;
    @Column(name = "server_id")
    private UUID serverId;
    private String name;
    private String description;
    private String owner;
    @Column(name = "identity_kind")
    private String identityKind;
    @Column(name = "identity_value")
    private String identityValue;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Column(name = "updated_by")
    private String updatedBy;

    protected Application() {
    }

    public Application(UUID serverId, String name, String description, String owner, String identityKind, String identityValue,
            String by, Instant now) {
        this.id = UUID.randomUUID();
        this.serverId = serverId;
        this.createdAt = now;
        this.createdBy = by;
        update(name, description, owner, identityKind, identityValue, by, now);
    }

    public void update(String name, String description, String owner, String identityKind, String identityValue, String by,
            Instant now) {
        this.name = name;
        this.description = description;
        this.owner = owner;
        this.identityKind = identityKind;
        this.identityValue = identityValue;
        this.updatedAt = now;
        this.updatedBy = by;
    }

    /** "nginx (Linux user www-data)" - kept on rules so history survives renames. */
    public String label() {
        String kind = switch (identityKind) {
            case "LINUX_USER" -> "Linux user";
            case "WINDOWS_PROGRAM" -> "program";
            default -> "Windows service";
        };
        return name + " (" + kind + " " + identityValue + ")";
    }

    public UUID getId() { return id; }
    public UUID getServerId() { return serverId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getOwner() { return owner; }
    public String getIdentityKind() { return identityKind; }
    public String getIdentityValue() { return identityValue; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
