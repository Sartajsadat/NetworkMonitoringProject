package gov.mot.netmon.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.agent.AgentProtocol.AgentConfig;
import gov.mot.netmon.agent.AgentProtocol.Report;
import gov.mot.netmon.agent.AgentProtocol.ReportResponse;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.common.ClientInfo;
import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.metrics.MetricWriter;
import gov.mot.netmon.metrics.SnapshotStore;
import gov.mot.netmon.server.Server;
import gov.mot.netmon.server.ServerRepository;

/** Ingests agent reports: heartbeat, latest snapshot and metric history. */
@Service
public class AgentReportService {

    private static final Logger log = LoggerFactory.getLogger(AgentReportService.class);
    /** Agents buffer reports while the platform is unreachable; older ones are refused. */
    static final Duration MAX_REPORT_AGE = Duration.ofHours(48);
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final AgentRepository agents;
    private final ServerRepository servers;
    private final ServerAgentConfigRepository configs;
    private final SnapshotStore snapshots;
    private final MetricWriter metricWriter;
    private final gov.mot.netmon.event.ReportEventDetector detector;
    private final gov.mot.netmon.event.AgentConnectionMonitor connections;
    private final gov.mot.netmon.network.ReportAddresses addresses;
    private final gov.mot.netmon.firewall.FirewallVerifier firewall;
    private final Clock clock;
    private final int defaultInterval;

    public AgentReportService(AgentRepository agents, ServerRepository servers, ServerAgentConfigRepository configs,
            SnapshotStore snapshots, MetricWriter metricWriter, gov.mot.netmon.event.ReportEventDetector detector,
            gov.mot.netmon.event.AgentConnectionMonitor connections, gov.mot.netmon.network.ReportAddresses addresses,
            gov.mot.netmon.firewall.FirewallVerifier firewall, Clock clock, AppProperties props) {
        this.firewall = firewall;
        this.addresses = addresses;
        this.detector = detector;
        this.connections = connections;
        this.agents = agents;
        this.servers = servers;
        this.configs = configs;
        this.snapshots = snapshots;
        this.metricWriter = metricWriter;
        this.clock = clock;
        this.defaultInterval = props.agents().defaultReportInterval();
    }

    @Transactional
    public ReportResponse ingest(AgentPrincipal principal, Report report) {
        Instant now = clock.instant();
        if (report.collectedAt().isAfter(now.plus(MAX_CLOCK_SKEW))) {
            throw ApiException.badRequest("CLOCK_SKEW",
                    "Report time is in the future. Synchronise the server clock (NTP).");
        }
        if (report.collectedAt().isBefore(now.minus(MAX_REPORT_AGE))) {
            throw ApiException.badRequest("REPORT_TOO_OLD", "Report is older than " + MAX_REPORT_AGE.toHours() + " hours");
        }
        Agent agent = agents.findById(principal.agentId()).orElseThrow(() -> ApiException.notFound("Agent"));
        Server server = servers.findById(principal.serverId()).orElseThrow(() -> ApiException.notFound("Server"));
        ServerAgentConfig config = configs.findById(server.getId())
                .orElseGet(() -> configs.save(new ServerAgentConfig(server.getId(), defaultInterval)));

        int errors = report.collectors() == null ? 0
                : (int) report.collectors().stream().filter(c -> !"OK".equals(c.status())).count();
        connections.onReport(agent, server, now);
        agent.recordReport(report, ClientInfo.currentIp(), now, errors);
        server.recordAgentReport(report.agentVersion(), errors > 0, now);

        // Firewall verification runs even while metrics are paused: rules stay enforced either way.
        firewall.onReport(server, report);
        if (!server.acceptsMetrics()) {
            return new ReportResponse(now, false, "Monitoring is " + server.getMonitoringStatus()
                    + " for this server; metrics are not stored.", toProtocolConfig(config, server));
        }
        Report previous = snapshots.find(server.getId()).map(SnapshotStore.Snapshot::report).orElse(null);
        detector.compare(server, previous, report);
        snapshots.upsertIfNewer(server.getId(), report, now);
        int rows = metricWriter.write(server.getId(), report);
        addresses.record(server, report);
        log.debug("Report from {} stored ({} samples, {} collector errors)", server.getName(), rows, errors);
        return new ReportResponse(now, true, null, toProtocolConfig(config, server));
    }

    static AgentConfig toProtocolConfig(ServerAgentConfig config, Server server) {
        return new AgentConfig(config.getReportIntervalSeconds(), List.copyOf(config.getMonitoredServices()),
                !server.acceptsMetrics());
    }
}
