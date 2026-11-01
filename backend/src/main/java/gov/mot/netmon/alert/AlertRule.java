package gov.mot.netmon.alert;

import java.time.Instant;
import java.util.UUID;

import gov.mot.netmon.event.EventSeverity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "alert_rules")
public class AlertRule {

    @Id
    private UUID id;
    private String name;
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type")
    private AlertRuleType ruleType;
    @Enumerated(EnumType.STRING)
    private EventSeverity severity;
    private Double threshold;
    @Column(name = "window_seconds")
    private int windowSeconds;
    @Column(name = "cooldown_seconds")
    private int cooldownSeconds;
    @Column(name = "server_id")
    private UUID serverId;
    @Column(name = "resource_filter")
    private String resourceFilter;
    private boolean enabled;
    private boolean builtin;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Column(name = "updated_by")
    private String updatedBy;

    protected AlertRule() {
    }

    public AlertRule(UUID id, String createdBy, Instant now) {
        this.id = id;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    public void apply(String name, String description, AlertRuleType type, EventSeverity severity, Double threshold,
            int windowSeconds, int cooldownSeconds, UUID serverId, String resourceFilter, boolean enabled, String by,
            Instant now) {
        this.name = name;
        this.description = description;
        this.ruleType = type;
        this.severity = severity;
        this.threshold = threshold;
        this.windowSeconds = windowSeconds;
        this.cooldownSeconds = cooldownSeconds;
        this.serverId = serverId;
        this.resourceFilter = resourceFilter;
        this.enabled = enabled;
        this.updatedBy = by;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public AlertRuleType getRuleType() { return ruleType; }
    public EventSeverity getSeverity() { return severity; }
    public Double getThreshold() { return threshold; }
    public int getWindowSeconds() { return windowSeconds; }
    public int getCooldownSeconds() { return cooldownSeconds; }
    public UUID getServerId() { return serverId; }
    public String getResourceFilter() { return resourceFilter; }
    public boolean isEnabled() { return enabled; }
    public boolean isBuiltin() { return builtin; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
