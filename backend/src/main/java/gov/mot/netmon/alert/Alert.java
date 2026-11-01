package gov.mot.netmon.alert;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.DynamicUpdate;

import gov.mot.netmon.event.EventCategory;
import gov.mot.netmon.event.EventSeverity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One incident of a rule on one resource (deduplicated while OPEN or ACKNOWLEDGED). */
@Entity
@Table(name = "alerts")
@DynamicUpdate
public class Alert {

    @Id
    private UUID id;
    @Column(name = "rule_id")
    private UUID ruleId;
    @Column(name = "rule_name")
    private String ruleName;
    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type")
    private AlertRuleType ruleType;
    @Enumerated(EnumType.STRING)
    private EventCategory category;
    @Enumerated(EnumType.STRING)
    private EventSeverity severity;
    @Column(name = "server_id")
    private UUID serverId;
    @Column(name = "server_name")
    private String serverName;
    private String resource;
    @Column(name = "dedup_key")
    private String dedupKey;
    @Enumerated(EnumType.STRING)
    private AlertStatus status;
    @Column(name = "condition_text")
    private String conditionText;
    private Double threshold;
    @Column(name = "window_seconds")
    private int windowSeconds;
    @Column(name = "first_triggered_at")
    private Instant firstTriggeredAt;
    @Column(name = "last_triggered_at")
    private Instant lastTriggeredAt;
    @Column(name = "last_value")
    private Double lastValue;
    @Column(name = "peak_value")
    private Double peakValue;
    private int evaluations;
    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;
    @Column(name = "acknowledged_by")
    private String acknowledgedBy;
    @Column(name = "ack_comment")
    private String ackComment;
    @Column(name = "resolved_at")
    private Instant resolvedAt;
    @Column(name = "resolved_by")
    private String resolvedBy;
    @Column(name = "resolution_type")
    private String resolutionType;
    private String resolution;

    protected Alert() {
    }

    public Alert(AlertRule rule, String dedupKey, UUID serverId, String serverName, String resource, String conditionText,
            Double value, Instant now) {
        this.id = UUID.randomUUID();
        this.ruleId = rule.getId();
        this.ruleName = rule.getName();
        this.ruleType = rule.getRuleType();
        this.category = rule.getRuleType().category;
        this.severity = rule.getSeverity();
        this.serverId = serverId;
        this.serverName = serverName;
        this.resource = resource;
        this.dedupKey = dedupKey;
        this.status = AlertStatus.OPEN;
        this.conditionText = cut(conditionText, 500);
        this.threshold = rule.getThreshold();
        this.windowSeconds = rule.getWindowSeconds();
        this.firstTriggeredAt = now;
        this.lastTriggeredAt = now;
        this.lastValue = value;
        this.peakValue = value;
        this.evaluations = 1;
    }

    /** The condition still holds. */
    public void stillTriggered(Double value, String conditionText, Instant now) {
        this.lastTriggeredAt = now;
        this.lastValue = value;
        if (value != null && (peakValue == null || higherIsWorse(value))) peakValue = value;
        this.conditionText = cut(conditionText, 500);
        this.evaluations++;
    }

    private boolean higherIsWorse(double value) {
        return value > peakValue;
    }

    public void acknowledge(String by, String comment, Instant now) {
        this.status = AlertStatus.ACKNOWLEDGED;
        this.acknowledgedBy = by;
        this.acknowledgedAt = now;
        this.ackComment = cut(comment, 1000);
    }

    public void resolve(String type, String by, String resolution, Instant now) {
        this.status = AlertStatus.RESOLVED;
        this.resolutionType = type;
        this.resolvedBy = by;
        this.resolvedAt = now;
        this.resolution = cut(resolution, 1000);
    }

    public boolean isActive() {
        return status != AlertStatus.RESOLVED;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    public UUID getId() { return id; }
    public UUID getRuleId() { return ruleId; }
    public String getRuleName() { return ruleName; }
    public AlertRuleType getRuleType() { return ruleType; }
    public EventCategory getCategory() { return category; }
    public EventSeverity getSeverity() { return severity; }
    public UUID getServerId() { return serverId; }
    public String getServerName() { return serverName; }
    public String getResource() { return resource; }
    public String getDedupKey() { return dedupKey; }
    public AlertStatus getStatus() { return status; }
    public String getConditionText() { return conditionText; }
    public Double getThreshold() { return threshold; }
    public int getWindowSeconds() { return windowSeconds; }
    public Instant getFirstTriggeredAt() { return firstTriggeredAt; }
    public Instant getLastTriggeredAt() { return lastTriggeredAt; }
    public Double getLastValue() { return lastValue; }
    public Double getPeakValue() { return peakValue; }
    public int getEvaluations() { return evaluations; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public String getAcknowledgedBy() { return acknowledgedBy; }
    public String getAckComment() { return ackComment; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getResolvedBy() { return resolvedBy; }
    public String getResolutionType() { return resolutionType; }
    public String getResolution() { return resolution; }
}
