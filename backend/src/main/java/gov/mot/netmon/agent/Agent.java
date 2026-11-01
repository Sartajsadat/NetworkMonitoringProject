package gov.mot.netmon.agent;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A monitoring agent bound to one server. Only the SHA-256 of its secret is stored. */
@Entity
@Table(name = "agents")
public class Agent {

    @Id
    private UUID id;

    @Column(name = "server_id", nullable = false, unique = true)
    private UUID serverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentStatus status;

    @Column(name = "secret_hash", nullable = false)
    private String secretHash;

    @Column(name = "previous_secret_hash")
    private String previousSecretHash;

    @Column(name = "previous_secret_valid_until")
    private Instant previousSecretValidUntil;

    @Column(name = "agent_version")
    private String agentVersion;

    private String platform;

    @Column(name = "os_name")
    private String osName;

    @Column(name = "os_version")
    private String osVersion;

    private String kernel;

    private String architecture;

    @Column(name = "reported_hostname")
    private String reportedHostname;

    @Column(name = "machine_id")
    private String machineId;

    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt;

    @Column(name = "enrolled_from_ip")
    private String enrolledFromIp;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "last_report_ip")
    private String lastReportIp;

    @Column(name = "last_error_count", nullable = false)
    private int lastErrorCount;

    @Column(name = "credential_rotated_at")
    private Instant credentialRotatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private String revokedBy;

    protected Agent() {
    }

    public Agent(UUID serverId) {
        this.id = UUID.randomUUID();
        this.serverId = serverId;
    }

    /** (Re-)enrolls: new identity details and secret, active again. */
    public void enroll(String secretHash, AgentProtocol.EnrollRequest req, String ip, Instant now) {
        this.status = AgentStatus.ACTIVE;
        this.secretHash = secretHash;
        this.previousSecretHash = null;
        this.previousSecretValidUntil = null;
        this.agentVersion = req.agentVersion();
        this.platform = req.platform();
        this.osName = req.osName();
        this.osVersion = req.osVersion();
        this.kernel = req.kernel();
        this.architecture = req.architecture();
        this.reportedHostname = req.hostname();
        this.machineId = req.machineId();
        this.enrolledAt = now;
        this.enrolledFromIp = ip;
        this.lastSeenAt = null;
        this.lastErrorCount = 0;
        this.credentialRotatedAt = null;
        this.revokedAt = null;
        this.revokedBy = null;
    }

    public void rotate(String newSecretHash, Instant now, Instant previousValidUntil) {
        this.previousSecretHash = this.secretHash;
        this.previousSecretValidUntil = previousValidUntil;
        this.secretHash = newSecretHash;
        this.credentialRotatedAt = now;
    }

    public void revoke(String by, Instant now) {
        this.status = AgentStatus.REVOKED;
        this.revokedAt = now;
        this.revokedBy = by;
        this.previousSecretHash = null;
        this.previousSecretValidUntil = null;
    }

    public void recordReport(AgentProtocol.Report r, String ip, Instant now, int errorCount) {
        this.lastSeenAt = now;
        this.lastReportIp = ip;
        this.agentVersion = r.agentVersion();
        this.lastErrorCount = errorCount;
        if (r.host() != null) {
            this.reportedHostname = r.host().hostname();
            this.platform = r.host().platform();
            if (r.host().osName() != null) this.osName = r.host().osName();
            if (r.host().osVersion() != null) this.osVersion = r.host().osVersion();
            if (r.host().kernel() != null) this.kernel = r.host().kernel();
            if (r.host().architecture() != null) this.architecture = r.host().architecture();
        }
    }

    public boolean isActive() {
        return status == AgentStatus.ACTIVE;
    }

    public UUID getId() { return id; }
    public UUID getServerId() { return serverId; }
    public AgentStatus getStatus() { return status; }
    public String getSecretHash() { return secretHash; }
    public String getPreviousSecretHash() { return previousSecretHash; }
    public Instant getPreviousSecretValidUntil() { return previousSecretValidUntil; }
    public String getAgentVersion() { return agentVersion; }
    public String getPlatform() { return platform; }
    public String getOsName() { return osName; }
    public String getOsVersion() { return osVersion; }
    public String getKernel() { return kernel; }
    public String getArchitecture() { return architecture; }
    public String getReportedHostname() { return reportedHostname; }
    public String getMachineId() { return machineId; }
    public Instant getEnrolledAt() { return enrolledAt; }
    public String getEnrolledFromIp() { return enrolledFromIp; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public String getLastReportIp() { return lastReportIp; }
    public int getLastErrorCount() { return lastErrorCount; }
    public Instant getCredentialRotatedAt() { return credentialRotatedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRevokedBy() { return revokedBy; }
}
