package gov.mot.netmon.agent.model;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Report sent to the platform (protocol version 1, see docs/AGENT-PROTOCOL.md).
 * Null means "not measured" and is omitted from the JSON; it is never replaced by zero.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Report(
        String agentVersion,
        Instant collectedAt,
        Integer intervalSeconds,
        Host host,
        Cpu cpu,
        Memory memory,
        List<Disk> disks,
        List<DiskIo> diskIo,
        List<NetworkInterface> network,
        List<Service> services,
        List<ProcessInfo> processes,
        List<CollectorStatus> collectors,
        Capture capture,
        List<Neighbor> neighbors) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Host(String hostname, String platform, String osName, String osVersion, String kernel,
            String architecture, Long uptimeSeconds, Instant bootTime, Integer cpuCount) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cpu(Double usagePercent, Double iowaitPercent, List<Double> perCore, Double load1, Double load5,
            Double load15, Integer runQueue) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Memory(Long totalBytes, Long availableBytes, Long usedBytes, Double usedPercent, Long swapTotalBytes,
            Long swapUsedBytes, Double swapUsedPercent) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Disk(String mount, String device, String fsType, Long totalBytes, Long usedBytes, Long freeBytes,
            Double usedPercent) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DiskIo(String device, Double readBytesPerSec, Double writeBytesPerSec, Double readOpsPerSec,
            Double writeOpsPerSec, Double busyPercent) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NetworkInterface(String name, String operState, String macAddress, List<String> addresses,
            Long speedMbps, Integer mtu, Long rxBytes, Long txBytes, Long rxPackets, Long txPackets, Long rxErrors,
            Long txErrors, Long rxDropped, Long txDropped, Double rxBitsPerSec, Double txBitsPerSec,
            Double rxPacketsPerSec, Double txPacketsPerSec, Double errorsPerSec, Double dropsPerSec) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Service(String name, String status, String loadState, String activeState, String subState,
            Instant since) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProcessInfo(long pid, String name, String user, Double cpuPercent, Long memoryBytes,
            Double memoryPercent, String state, Instant startedAt) {
    }

    /** Packet capture capability of this server (Phase 4). Capture is off unless enabled locally. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Capture(boolean enabled, boolean available, String message, List<CaptureInterface> interfaces,
            Integer maxDurationSeconds, Long maxBytes, String dumpcapVersion) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CaptureInterface(String name, String description) {
    }

    /** Neighbour (ARP) table entry: an address on a local network and its MAC (Phase 6). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Neighbor(String ip, String mac, String interfaceName, String state) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CollectorStatus(String name, String status, String message) {
    }
}
