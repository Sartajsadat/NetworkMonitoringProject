package gov.mot.netmon.network;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class NetworkDtos {

    private NetworkDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubnetView(UUID id, String cidr, int ipVersion, int prefix, String name, String description, Integer vlanId,
            String gateway, String location, String organization, String size, String usable, long knownAddresses,
            long seenLast24h, Double usedPercent, Instant updatedAt, String updatedBy) {
    }

    public record SubnetRequest(
            @NotBlank @Size(max = 50) String cidr,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @Min(1) @Max(4094) Integer vlanId,
            @Size(max = 45) String gateway,
            @Size(max = 120) String location,
            @Size(max = 150) String organization) {
    }

    /**
     * Status from passive observation only. NEVER_SEEN / STALE do not mean the address is free:
     * a device may simply not have been observed by an agent, a neighbour table or a flow exporter.
     */
    public enum Seen { SEEN_NOW, SEEN_RECENTLY, NOT_SEEN_RECENTLY, NEVER_SEEN }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IpView(UUID id, String address, int ipVersion, UUID subnetId, String subnetCidr, String subnetName,
            UUID serverId, String serverName, UUID deviceId, String deviceName, String macAddress, String hostname,
            String allocation, List<String> sources, Instant firstSeenAt, Instant lastSeenAt, String lastSeenSource, Seen seen,
            String notes) {
    }

    public record IpRequest(
            @NotBlank @Size(max = 45) String address,
            @Size(max = 17) String macAddress,
            @Size(max = 253) String hostname,
            @Size(max = 15) String allocation,
            @Size(max = 1000) String notes) {
    }

    public record AlertRef(UUID id, String ruleName, String status, String severity, String resource, Instant lastTriggeredAt) {
    }

    public record CaptureConversation(UUID captureId, String captureName, long packets, Instant first, Instant last) {
    }

    public record Peer(String address, long bytes, long flows) {
    }

    public record FlowSummary(Instant from, Instant to, long bytesSent, long bytesReceived, long flows, List<Peer> topPeers) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IpDetail(IpView ip, List<AlertRef> alerts, List<CaptureConversation> captures, boolean capturesHidden,
            FlowSummary flows, boolean flowsHidden) {
    }

    public record ImportResult(int imported, int updated, List<String> errors) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeviceView(UUID id, String name, String deviceType, String vendor, String model, String managementIp,
            String location, String description, boolean flowExport, String exporterIp, Instant lastFlowAt, long flowsReceived,
            long flowsDropped, Integer lastFlowVersion, Integer samplingInterval, Instant updatedAt) {

        static DeviceView of(NetworkDevice d) {
            return new DeviceView(d.getId(), d.getName(), d.getDeviceType(), d.getVendor(), d.getModel(), d.getManagementIp(),
                    d.getLocation(), d.getDescription(), d.isFlowExport(), d.getExporterIp(), d.getLastFlowAt(),
                    d.getFlowsReceived(), d.getFlowsDropped(), d.getLastFlowVersion(), d.getSamplingInterval(), d.getUpdatedAt());
        }
    }

    public record DeviceRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 20) String deviceType,
            @Size(max = 80) String vendor,
            @Size(max = 80) String model,
            @Size(max = 45) String managementIp,
            @Size(max = 120) String location,
            @Size(max = 500) String description,
            boolean flowExport,
            @Size(max = 45) String exporterIp) {
    }
}
