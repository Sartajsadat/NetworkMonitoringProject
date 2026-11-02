package gov.mot.netmon.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wire format between monitoring agents and the platform (version 1). See docs/AGENT-PROTOCOL.md.
 * Every collection and string is bounded so a misbehaving agent cannot flood the database.
 * Fields are nullable: null means "not measured" (for example rates on the first sample),
 * never zero.
 */
public final class AgentProtocol {

    private AgentProtocol() {
    }

    /** Service names: systemd units (nginx.service) and Windows services (W3SVC, MSSQL$SQLEXPRESS). */
    public static final String NAME_PATTERN = "^[\\p{Alnum}@._:/+$-]{1,128}$";
    /**
     * Interface and disk device names. Windows adapter names contain spaces and brackets
     * ("vEthernet (Default Switch)"), so any printable text is accepted; control characters are not.
     * These values are only ever stored and queried as bound parameters.
     */
    public static final String RESOURCE_PATTERN = "^[^\\p{Cntrl}]{1,128}$";

    // ---------------- enrollment ----------------

    public record EnrollRequest(
            @NotBlank @Size(max = 200) String token,
            @NotBlank @Size(max = 253) String hostname,
            @NotBlank @Pattern(regexp = "LINUX|WINDOWS|OTHER") String platform,
            @Size(max = 120) String osName,
            @Size(max = 120) String osVersion,
            @Size(max = 120) String kernel,
            @Size(max = 40) String architecture,
            @NotBlank @Size(max = 40) String agentVersion,
            @Size(max = 64) String machineId) {

        @Override
        public String toString() {
            return "EnrollRequest[hostname=" + hostname + ", platform=" + platform + "]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EnrollResponse(UUID agentId, String secret, UUID serverId, String serverName, AgentConfig config) {
        @Override
        public String toString() {
            return "EnrollResponse[agentId=" + agentId + "]";
        }
    }

    public record RotateResponse(String secret, Instant previousSecretValidUntil) {
        @Override
        public String toString() {
            return "RotateResponse[***]";
        }
    }

    /** Settings the platform sends back with every report response. */
    public record AgentConfig(int reportIntervalSeconds, List<String> services, boolean monitoringPaused) {
    }

    public record ReportResponse(Instant serverTime, boolean accepted, String note, AgentConfig config) {
    }

    // ---------------- report ----------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Report(
            @NotBlank @Size(max = 40) String agentVersion,
            @NotNull Instant collectedAt,
            @Min(1) @Max(3600) Integer intervalSeconds,
            @NotNull @Valid Host host,
            @Valid Cpu cpu,
            @Valid Memory memory,
            @Size(max = 64) List<@Valid Disk> disks,
            @Size(max = 64) List<@Valid DiskIo> diskIo,
            @Size(max = 64) List<@Valid NetworkInterface> network,
            @Size(max = 100) List<@Valid Service> services,
            @Size(max = 30) List<@Valid ProcessInfo> processes,
            @Size(max = 30) List<@Valid CollectorStatus> collectors,
            @Valid Capture capture,
            @Size(max = 512) List<@Valid Neighbor> neighbors) {
    }

    /** One entry of the server's neighbour (ARP / NDP) table: an address on a local network and its MAC. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Neighbor(
            @NotBlank @Size(max = 45) String ip,
            @Size(max = 17) @Pattern(regexp = "^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$") String mac,
            @Size(max = 128) @Pattern(regexp = RESOURCE_PATTERN) String interfaceName,
            @Size(max = 20) String state) {
    }

    /**
     * Packet-capture capability of the agent (phase 4). Capture is off unless the server operator
     * enabled it locally and listed the interfaces that may be captured.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Capture(
            boolean enabled,
            boolean available,
            @Size(max = 300) String message,
            @Size(max = 64) List<@Valid CaptureInterface> interfaces,
            @PositiveOrZero Integer maxDurationSeconds,
            @PositiveOrZero Long maxBytes,
            @Size(max = 80) String dumpcapVersion) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CaptureInterface(@NotBlank @Pattern(regexp = RESOURCE_PATTERN) String name, @Size(max = 200) String description) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Host(
            @NotBlank @Size(max = 253) String hostname,
            @NotBlank @Pattern(regexp = "LINUX|WINDOWS|OTHER") String platform,
            @Size(max = 120) String osName,
            @Size(max = 120) String osVersion,
            @Size(max = 120) String kernel,
            @Size(max = 40) String architecture,
            @PositiveOrZero Long uptimeSeconds,
            Instant bootTime,
            @Min(1) @Max(4096) Integer cpuCount) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cpu(
            @DecimalMin("0") @DecimalMax("100") Double usagePercent,
            @DecimalMin("0") @DecimalMax("100") Double iowaitPercent,
            @Size(max = 4096) List<@DecimalMin("0") @DecimalMax("100") Double> perCore,
            @PositiveOrZero Double load1,
            @PositiveOrZero Double load5,
            @PositiveOrZero Double load15,
            @PositiveOrZero Integer runQueue) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Memory(
            @PositiveOrZero Long totalBytes,
            @PositiveOrZero Long availableBytes,
            @PositiveOrZero Long usedBytes,
            @DecimalMin("0") @DecimalMax("100") Double usedPercent,
            @PositiveOrZero Long swapTotalBytes,
            @PositiveOrZero Long swapUsedBytes,
            @DecimalMin("0") @DecimalMax("100") Double swapUsedPercent) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Disk(
            @NotBlank @Size(max = 128) String mount,
            @Size(max = 128) String device,
            @Size(max = 40) String fsType,
            @PositiveOrZero Long totalBytes,
            @PositiveOrZero Long usedBytes,
            @PositiveOrZero Long freeBytes,
            @DecimalMin("0") @DecimalMax("100") Double usedPercent) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DiskIo(
            @NotBlank @Pattern(regexp = RESOURCE_PATTERN) String device,
            @PositiveOrZero Double readBytesPerSec,
            @PositiveOrZero Double writeBytesPerSec,
            @PositiveOrZero Double readOpsPerSec,
            @PositiveOrZero Double writeOpsPerSec,
            @DecimalMin("0") @DecimalMax("100") Double busyPercent) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NetworkInterface(
            @NotBlank @Pattern(regexp = RESOURCE_PATTERN) String name,
            @Size(max = 20) String operState,
            @Size(max = 32) String macAddress,
            @Size(max = 32) List<@Size(max = 64) String> addresses,
            @PositiveOrZero Long speedMbps,
            @PositiveOrZero Integer mtu,
            @PositiveOrZero Long rxBytes,
            @PositiveOrZero Long txBytes,
            @PositiveOrZero Long rxPackets,
            @PositiveOrZero Long txPackets,
            @PositiveOrZero Long rxErrors,
            @PositiveOrZero Long txErrors,
            @PositiveOrZero Long rxDropped,
            @PositiveOrZero Long txDropped,
            @PositiveOrZero Double rxBitsPerSec,
            @PositiveOrZero Double txBitsPerSec,
            @PositiveOrZero Double rxPacketsPerSec,
            @PositiveOrZero Double txPacketsPerSec,
            @PositiveOrZero Double errorsPerSec,
            @PositiveOrZero Double dropsPerSec) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Service(
            @NotBlank @Pattern(regexp = NAME_PATTERN) String name,
            @NotBlank @Pattern(regexp = "RUNNING|STOPPED|FAILED|STARTING|STOPPING|NOT_FOUND|UNKNOWN") String status,
            @Size(max = 40) String loadState,
            @Size(max = 40) String activeState,
            @Size(max = 40) String subState,
            Instant since) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProcessInfo(
            @PositiveOrZero long pid,
            @NotBlank @Size(max = 64) String name,
            @Size(max = 64) String user,
            @DecimalMin("0") Double cpuPercent,
            @PositiveOrZero Long memoryBytes,
            @DecimalMin("0") @DecimalMax("100") Double memoryPercent,
            @Size(max = 4) String state,
            Instant startedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CollectorStatus(
            @NotBlank @Size(max = 40) String name,
            @NotBlank @Pattern(regexp = "OK|UNSUPPORTED|UNAVAILABLE|PERMISSION_DENIED|ERROR") String status,
            @Size(max = 300) String message) {
    }
}
