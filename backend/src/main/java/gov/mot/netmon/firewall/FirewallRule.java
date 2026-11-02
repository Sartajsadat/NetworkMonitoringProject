package gov.mot.netmon.firewall;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "firewall_rules")
@DynamicUpdate
public class FirewallRule {

    public static final java.util.Set<String> ACTIVE = java.util.Set.of("APPLIED", "DRY_RUN", "UNVERIFIED", "APPROVED",
            "REMOVAL_PENDING", "REQUESTED");

    @Id
    private UUID id;
    @Column(name = "integration_id")
    private UUID integrationId;
    private String tag;
    private String action;
    private String direction;
    @Column(name = "remote_cidr")
    private String remoteCidr;
    @Column(name = "local_cidr")
    private String localCidr;
    private String protocol;
    @Column(name = "local_port")
    private String localPort;
    @Column(name = "remote_port")
    private String remotePort;
    private String reason;
    private String reference;
    @Column(name = "expires_at")
    private Instant expiresAt;
    private String status;
    @Column(name = "status_message")
    private String statusMessage;
    @Column(name = "mode_at_apply")
    private String modeAtApply;
    private String preview;
    private String warnings;
    @Column(name = "agent_command")
    private String agentCommand;
    @Column(name = "requested_by")
    private String requestedBy;
    @Column(name = "requested_at")
    private Instant requestedAt;
    @Column(name = "decided_by")
    private String decidedBy;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "applied_at")
    private Instant appliedAt;
    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;
    @Column(name = "removal_requested_by")
    private String removalRequestedBy;
    @Column(name = "removal_requested_at")
    private Instant removalRequestedAt;
    @Column(name = "removal_reason")
    private String removalReason;
    @Column(name = "removed_at")
    private Instant removedAt;
    private boolean expiring;
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected FirewallRule() {
    }

    public FirewallRule(UUID integrationId, String tag, RuleSpec s, String reason, String reference, Instant expiresAt,
            String preview, String warnings, String by, Instant now) {
        this.id = UUID.randomUUID();
        this.integrationId = integrationId;
        this.tag = tag;
        this.action = s.action();
        this.direction = s.direction();
        this.remoteCidr = s.remoteCidr();
        this.localCidr = s.localCidr();
        this.protocol = s.protocol();
        this.localPort = s.localPort();
        this.remotePort = s.remotePort();
        this.reason = reason;
        this.reference = reference;
        this.expiresAt = expiresAt;
        this.preview = cut(preview, 2000);
        this.warnings = cut(warnings, 2000);
        this.requestedBy = by;
        this.requestedAt = now;
        this.status = "REQUESTED";
        this.updatedAt = now;
    }

    public RuleSpec spec() {
        return new RuleSpec(action, direction, remoteCidr, localCidr, protocol, localPort, remotePort);
    }

    public void status(String status, String message, Instant now) {
        this.status = status;
        this.statusMessage = cut(message, 1000);
        this.updatedAt = now;
        if ("APPLIED".equals(status) || "DRY_RUN".equals(status)) {
            if (appliedAt == null) appliedAt = now;
            lastVerifiedAt = now;
        }
        if ("REMOVED".equals(status) || "EXPIRED".equals(status)) removedAt = now;
    }

    public void decide(String by, Instant now) {
        this.decidedBy = by;
        this.decidedAt = now;
    }

    public void approved(String mode, Instant now) {
        this.modeAtApply = mode;
        status("APPROVED", null, now);
    }

    public void requestRemoval(String by, String reason, boolean expiry, Instant now) {
        this.removalRequestedBy = by;
        this.removalRequestedAt = now;
        this.removalReason = cut(reason, 500);
        this.expiring = expiry;
        this.updatedAt = now;
    }

    public void verified(Instant now) {
        this.lastVerifiedAt = now;
    }

    public void agentCommand(String c) {
        if (c != null) this.agentCommand = cut(c, 2000);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    public UUID getId() { return id; }
    public UUID getIntegrationId() { return integrationId; }
    public String getTag() { return tag; }
    public String getAction() { return action; }
    public String getDirection() { return direction; }
    public String getRemoteCidr() { return remoteCidr; }
    public String getLocalCidr() { return localCidr; }
    public String getProtocol() { return protocol; }
    public String getLocalPort() { return localPort; }
    public String getRemotePort() { return remotePort; }
    public String getReason() { return reason; }
    public String getReference() { return reference; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getStatus() { return status; }
    public String getStatusMessage() { return statusMessage; }
    public String getModeAtApply() { return modeAtApply; }
    public String getPreview() { return preview; }
    public String getWarnings() { return warnings; }
    public String getAgentCommand() { return agentCommand; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getAppliedAt() { return appliedAt; }
    public Instant getLastVerifiedAt() { return lastVerifiedAt; }
    public String getRemovalRequestedBy() { return removalRequestedBy; }
    public Instant getRemovalRequestedAt() { return removalRequestedAt; }
    public String getRemovalReason() { return removalReason; }
    public Instant getRemovedAt() { return removedAt; }
    public boolean isExpiring() { return expiring; }
    public Instant getUpdatedAt() { return updatedAt; }
}
