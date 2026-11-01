package gov.mot.netmon.server;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import gov.mot.netmon.server.ServerEnums.GroupType;

@Entity
@Table(name = "server_groups")
public class ServerGroup {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "group_type", nullable = false)
    private GroupType groupType;

    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ServerGroup() {
    }

    public ServerGroup(String name, GroupType groupType, String description) {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        update(name, groupType, description);
    }

    public void update(String name, GroupType groupType, String description) {
        this.name = name;
        this.groupType = groupType;
        this.description = description;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public GroupType getGroupType() { return groupType; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
