package gov.mot.netmon.capture;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonInclude;

import gov.mot.netmon.agent.AgentProtocol;
import gov.mot.netmon.server.ServerEnums.ConnectionStatus;
import gov.mot.netmon.server.ServerEnums.OsFamily;

public final class CaptureDtos {

    private CaptureDtos() {
    }

    public record CreateCaptureRequest(
            @NotNull UUID serverId,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 1000) String description,
            @Size(max = 120) String reference,
            @NotBlank @Size(min = 10, max = 500) String reason,
            @NotBlank @Size(max = 128) String interfaceName,
            @Size(max = 512) String captureFilter,
            @NotNull @Min(10) @Max(86400) Integer durationSeconds,
            @NotNull @Min(1) @Max(102400) Integer maxMegabytes,
            @Min(64) @Max(65535) Integer snaplen,
            @Min(1) @Max(365) Integer retentionDays) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CaptureView(UUID id, UUID serverId, String serverName, String serverHostname, String name,
            String description, String reference, String reason, String interfaceName, String captureFilter, int snaplen,
            int durationSeconds, long maxBytes, int retentionDays, CaptureStatus status, String statusMessage,
            String requestedBy, Instant requestedAt, Instant startedAt, Instant endedAt, String stopRequestedBy,
            Instant stopRequestedAt, Long agentPackets, long bytesStored, int segmentsCount, long packetsIndexed,
            boolean indexTruncated, Instant expiresAt, Instant filesDeletedAt, boolean filesAvailable) {
    }

    public record CaptureInterfaceView(String name, String description) {
    }

    /** Whether a server can capture right now, and why not. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Capability(UUID serverId, String serverName, String hostname, OsFamily osFamily,
            ConnectionStatus connectionStatus, String state, String message, List<CaptureInterfaceView> interfaces,
            Integer maxDurationSeconds, Long maxBytes, boolean busy) {
        // state: AVAILABLE, NOT_ENABLED, UNAVAILABLE, NO_AGENT, OFFLINE
    }

    public record Limits(int maxDurationSeconds, long maxBytesPerCapture, int defaultSnaplen, int defaultRetentionDays,
            int maxRetentionDays, long quotaBytes, long usedBytes, long reservedBytes, boolean analysisAvailable) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PacketRow(long frameNo, Instant time, String src, String dst, Integer srcPort, Integer dstPort,
            String protocol, int length, String tcpFlags, boolean retransmission, boolean reset, String dnsQuery,
            String info) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PacketPage(List<PacketRow> content, int page, int size, long totalElements, int totalPages,
            String displayFilter, boolean displayFilterTruncated, long packetsIndexed, boolean indexTruncated) {
    }

    public record PacketSearch(
            @Size(max = 64) String src, @Size(max = 64) String dst, @Size(max = 64) String host,
            @Size(max = 40) String protocol, @Min(0) @Max(65535) Integer port, @Min(0) Integer minLength,
            @Min(0) Integer maxLength, Instant from, Instant to, @Size(max = 200) String text,
            @Size(max = 1000) String displayFilter, @Min(0) Integer page, @Min(1) @Max(500) Integer size, Boolean descending) {
    }

    public record PacketDetail(PacketRow packet, List<PdmlTree.TreeNode> layers) {
    }

    public record PacketBytes(long frameNo, String hex) {
    }

    public record FilterCheck(boolean valid, String message) {
    }

    public record CountRow(String key, long packets, long bytes) {
    }

    public record Conversation(String a, String b, long packets, long bytes, long packetsAtoB, long packetsBtoA,
            Instant first, Instant last, String protocols) {
    }

    public record TimeBucket(Instant t, long packets, long bytes, long bucketSeconds) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Statistics(long packets, long bytes, Instant first, Instant last, long tcpResets, long retransmissions,
            long tcpSyns, List<CountRow> protocols, List<CountRow> topSources, List<CountRow> topDestinations,
            List<CountRow> topPorts, List<Conversation> conversations, List<TimeBucket> timeline,
            List<CountRow> dnsQueries, boolean indexTruncated) {
    }

    // ---------------- agent side ----------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentTaskView(UUID id, String type, UUID captureId, String interfaceName, String captureFilter,
            Integer snaplen, Integer durationSeconds, Long maxBytes, java.util.Map<String, Object> firewall) {
    }

    public record AgentTasks(List<AgentTaskView> tasks) {
    }

    public record AgentStatusUpdate(@NotBlank String state, @Size(max = 1000) String message, Long packets) {
    }

    public record SegmentAck(boolean accepted, boolean stopRequested, long bytesStored, long maxBytes) {
    }

    static List<CaptureInterfaceView> interfaces(AgentProtocol.Capture c) {
        return c == null || c.interfaces() == null ? List.of()
                : c.interfaces().stream().map(i -> new CaptureInterfaceView(i.name(), i.description())).toList();
    }
}
