package gov.mot.netmon.metrics;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import gov.mot.netmon.agent.AgentProtocol;
import gov.mot.netmon.server.ServerEnums.ConnectionStatus;
import gov.mot.netmon.server.ServerEnums.Environment;
import gov.mot.netmon.server.ServerEnums.OsFamily;

public final class MetricDtos {

    private MetricDtos() {
    }

    /** One point. For rollups `v` is the average and min/max the extremes within the bucket. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Point(Instant t, double v, Double min, Double max) {
    }

    public record Series(String metric, String resource, String unit, List<Point> points) {
    }

    /**
     * @param resolution              RAW (as collected) or 5m (rollup averages)
     * @param expectedIntervalSeconds spacing between points when data is complete; larger gaps are missing data
     */
    public record History(UUID serverId, Instant from, Instant to, String resolution, int expectedIntervalSeconds,
            List<Series> series) {
    }

    /**
     * Latest report. `available=false` with a reason (NO_AGENT, NO_DATA) when nothing has been collected;
     * `stale=true` when the report is older than the online threshold.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Latest(UUID serverId, boolean available, String reason, ConnectionStatus connectionStatus,
            Instant collectedAt, Instant receivedAt, Long ageSeconds, boolean stale, AgentProtocol.Report report) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Slice<T>(UUID serverId, boolean available, Instant collectedAt, boolean stale, List<T> items) {
    }

    /** Fleet row: latest values for one server (processes omitted to keep the response small). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FleetEntry(UUID serverId, String name, String hostname, OsFamily osFamily, Environment environment,
            String organization, ConnectionStatus connectionStatus, Instant collectedAt, boolean stale,
            AgentProtocol.Host host, AgentProtocol.Cpu cpu, AgentProtocol.Memory memory,
            List<AgentProtocol.Disk> disks, List<AgentProtocol.DiskIo> diskIo,
            List<AgentProtocol.NetworkInterface> network, List<AgentProtocol.Service> services,
            List<AgentProtocol.CollectorStatus> collectors) {
    }
}
