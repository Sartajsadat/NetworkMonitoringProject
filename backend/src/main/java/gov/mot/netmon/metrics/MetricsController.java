package gov.mot.netmon.metrics;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import gov.mot.netmon.agent.AgentProtocol;
import gov.mot.netmon.agent.AgentProtocol.Report;
import gov.mot.netmon.metrics.MetricDtos.FleetEntry;
import gov.mot.netmon.metrics.MetricDtos.History;
import gov.mot.netmon.metrics.MetricDtos.Latest;
import gov.mot.netmon.metrics.MetricDtos.Slice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Read-only monitoring data (any signed-in role). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Metrics")
public class MetricsController {

    private final MetricQueryService service;

    public MetricsController(MetricQueryService service) {
        this.service = service;
    }

    @GetMapping("/servers/{id}/metrics")
    @Operation(summary = "Metric history. Ranges up to 24 h return raw samples, longer ranges 5-minute averages")
    public History history(@PathVariable UUID id, @RequestParam List<String> metric,
            @RequestParam(required = false) String resource,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return service.history(id, metric, resource, from, to);
    }

    @GetMapping("/servers/{id}/metrics/latest")
    @Operation(summary = "Most recent full report from the server's agent")
    public Latest latest(@PathVariable UUID id) {
        return service.latest(id);
    }

    @GetMapping("/servers/{id}/interfaces")
    public Slice<AgentProtocol.NetworkInterface> interfaces(@PathVariable UUID id) {
        return service.slice(id, Report::network);
    }

    @GetMapping("/servers/{id}/services")
    public Slice<AgentProtocol.Service> services(@PathVariable UUID id) {
        return service.slice(id, Report::services);
    }

    @GetMapping("/servers/{id}/processes")
    @Operation(summary = "Top processes by CPU and memory from the latest report (no command lines are collected)")
    public Slice<AgentProtocol.ProcessInfo> processes(@PathVariable UUID id) {
        return service.slice(id, Report::processes);
    }

    @GetMapping("/servers/{id}/disks")
    public Slice<AgentProtocol.Disk> disks(@PathVariable UUID id) {
        return service.slice(id, Report::disks);
    }

    @GetMapping("/monitoring/fleet")
    @Operation(summary = "Latest values for every server whose agent has reported")
    public List<FleetEntry> fleet() {
        return service.fleet();
    }
}
