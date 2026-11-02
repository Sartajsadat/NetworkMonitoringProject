package gov.mot.netmon.capture;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A typed instruction for an agent (START_CAPTURE / STOP_CAPTURE). Never a command line. */
@Entity
@Table(name = "agent_tasks")
public class AgentTask {

    @Id
    private UUID id;
    @Column(name = "server_id", nullable = false)
    private UUID serverId;
    @Column(name = "capture_id")
    private UUID captureId;
    @Column(name = "firewall_rule_id")
    private UUID firewallRuleId;
    @Column(name = "task_type", nullable = false)
    private String taskType;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;
    @Column(nullable = false)
    private String status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected AgentTask() {
    }

    public AgentTask(UUID serverId, UUID captureId, String taskType, Map<String, Object> payload, Instant now) {
        this.id = UUID.randomUUID();
        this.serverId = serverId;
        this.captureId = captureId;
        this.taskType = taskType;
        this.payload = payload;
        this.status = "PENDING";
        this.createdAt = now;
    }

    /** APPLY_FIREWALL_RULE / REMOVE_FIREWALL_RULE for one platform-managed rule. */
    public static AgentTask firewall(UUID serverId, UUID ruleId, String taskType, Map<String, Object> payload, Instant now) {
        AgentTask t = new AgentTask(serverId, null, taskType, payload, now);
        t.firewallRuleId = ruleId;
        return t;
    }

    public void delivered(Instant now) {
        status = "DELIVERED";
        deliveredAt = now;
    }

    public void cancel() {
        status = "CANCELLED";
    }

    public UUID getId() { return id; }
    public UUID getServerId() { return serverId; }
    public UUID getCaptureId() { return captureId; }
    public String getTaskType() { return taskType; }
    public UUID getFirewallRuleId() { return firewallRuleId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Map<String, Object> getPayload() { return payload; }
    public String getStatus() { return status; }
}
