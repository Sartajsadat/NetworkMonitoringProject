package gov.mot.netmon.capture;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "packet_captures")
// Write only changed columns: agent uploads, agent status reports and user actions touch different fields.
@org.hibernate.annotations.DynamicUpdate
public class PacketCapture {

    @Id
    private UUID id;
    @Column(name = "server_id", nullable = false)
    private UUID serverId;
    @Column(nullable = false)
    private String name;
    private String description;
    private String reference;
    @Column(nullable = false)
    private String reason;
    @Column(name = "interface_name", nullable = false)
    private String interfaceName;
    @Column(name = "capture_filter")
    private String captureFilter;
    @Column(nullable = false)
    private int snaplen;
    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;
    @Column(name = "max_bytes", nullable = false)
    private long maxBytes;
    @Column(name = "retention_days", nullable = false)
    private int retentionDays;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CaptureStatus status;
    @Column(name = "status_message")
    private String statusMessage;
    @Column(name = "requested_by", nullable = false)
    private String requestedBy;
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "ended_at")
    private Instant endedAt;
    @Column(name = "stop_requested_by")
    private String stopRequestedBy;
    @Column(name = "stop_requested_at")
    private Instant stopRequestedAt;
    @Column(name = "agent_packets")
    private Long agentPackets;
    @Column(name = "bytes_stored", nullable = false)
    private long bytesStored;
    @Column(name = "segments_count", nullable = false)
    private int segmentsCount;
    @Column(name = "packets_indexed", nullable = false)
    private long packetsIndexed;
    @Column(name = "index_truncated", nullable = false)
    private boolean indexTruncated;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "files_deleted_at")
    private Instant filesDeletedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PacketCapture() {
    }

    public PacketCapture(UUID serverId, String name, String description, String reference, String reason,
            String interfaceName, String captureFilter, int snaplen, int durationSeconds, long maxBytes,
            int retentionDays, String requestedBy, Instant now) {
        this.id = UUID.randomUUID();
        this.serverId = serverId;
        this.name = name;
        this.description = description;
        this.reference = reference;
        this.reason = reason;
        this.interfaceName = interfaceName;
        this.captureFilter = captureFilter;
        this.snaplen = snaplen;
        this.durationSeconds = durationSeconds;
        this.maxBytes = maxBytes;
        this.retentionDays = retentionDays;
        this.status = CaptureStatus.PENDING;
        this.requestedBy = requestedBy;
        this.requestedAt = now;
        this.expiresAt = now.plusSeconds(durationSeconds).plusSeconds(retentionDays * 86400L);
        this.updatedAt = now;
    }

    /** Applies a state change; returns false when the change is not allowed (e.g. from a final state). */
    public boolean transition(CaptureStatus next, String message, Instant now) {
        if (!status.isActive() && next != status) {
            return false;
        }
        if (next == CaptureStatus.RUNNING && startedAt == null) startedAt = now;
        if (!next.isActive() && endedAt == null) endedAt = now;
        status = next;
        if (message != null) statusMessage = message.length() > 1000 ? message.substring(0, 1000) : message;
        updatedAt = now;
        return true;
    }

    public void requestStop(String by, Instant now) {
        stopRequestedBy = by;
        stopRequestedAt = now;
        updatedAt = now;
    }

    public void addSegment(long size, Instant now) {
        bytesStored += size;
        segmentsCount++;
        updatedAt = now;
    }

    public void setAgentPackets(Long packets) {
        if (packets != null) agentPackets = packets;
    }

    public void markFilesDeleted(Instant now) {
        filesDeletedAt = now;
        bytesStored = 0;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getServerId() { return serverId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getReference() { return reference; }
    public String getReason() { return reason; }
    public String getInterfaceName() { return interfaceName; }
    public String getCaptureFilter() { return captureFilter; }
    public int getSnaplen() { return snaplen; }
    public int getDurationSeconds() { return durationSeconds; }
    public long getMaxBytes() { return maxBytes; }
    public int getRetentionDays() { return retentionDays; }
    public CaptureStatus getStatus() { return status; }
    public String getStatusMessage() { return statusMessage; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public String getStopRequestedBy() { return stopRequestedBy; }
    public Instant getStopRequestedAt() { return stopRequestedAt; }
    public Long getAgentPackets() { return agentPackets; }
    public long getBytesStored() { return bytesStored; }
    public int getSegmentsCount() { return segmentsCount; }
    public long getPacketsIndexed() { return packetsIndexed; }
    public boolean isIndexTruncated() { return indexTruncated; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getFilesDeletedAt() { return filesDeletedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
