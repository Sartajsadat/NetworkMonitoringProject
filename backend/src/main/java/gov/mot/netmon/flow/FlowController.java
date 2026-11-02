package gov.mot.netmon.flow;

import java.time.Instant;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import gov.mot.netmon.common.PageResponse;
import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.flow.FlowDtos.Filter;
import gov.mot.netmon.flow.FlowDtos.FlowView;
import gov.mot.netmon.flow.FlowDtos.Summary;
import gov.mot.netmon.network.NetworkAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Flow records (NetFlow/IPFIX summaries - not packets). Same access as packet captures. */
@RestController
@RequestMapping("/api/v1/network-flows")
@PreAuthorize(NetworkAccess.VIEW_TRAFFIC)
@Tag(name = "Network flows")
public class FlowController {

    private final FlowQueryService service;
    private final FlowCollector collector;
    private final AppProperties props;

    public FlowController(FlowQueryService service, FlowCollector collector, AppProperties props) {
        this.service = service;
        this.collector = collector;
        this.props = props;
    }

    @GetMapping("/summary")
    @Operation(summary = "Top sources, destinations, ports, protocols, conversations and traffic over time")
    public Summary summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID exporterId, @RequestParam(required = false) String ip,
            @RequestParam(required = false) String subnet, @RequestParam(required = false) Integer port,
            @RequestParam(required = false) Integer protocol) {
        return service.summary(new Filter(from, to, exporterId, ip, subnet, port, protocol));
    }

    @GetMapping
    public PageResponse<FlowView> records(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID exporterId, @RequestParam(required = false) String ip,
            @RequestParam(required = false) String subnet, @RequestParam(required = false) Integer port,
            @RequestParam(required = false) Integer protocol, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.records(new Filter(from, to, exporterId, ip, subnet, port, protocol), page, size);
    }

    public record CollectorStatus(boolean enabled, boolean listening, int netflowPort, int ipfixPort, long rejectedPackets) {
    }

    @GetMapping("/collector")
    public CollectorStatus collector() {
        var f = props.flows();
        return new CollectorStatus(f.enabled(), collector.isRunning(), f.netflowPort(), f.ipfixPort(), collector.rejectedPackets());
    }
}
