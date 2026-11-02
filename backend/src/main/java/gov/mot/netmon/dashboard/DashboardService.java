package gov.mot.netmon.dashboard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.audit.AuditLog;
import gov.mot.netmon.audit.AuditLogRepository;
import gov.mot.netmon.audit.AuditLogResponse;
import gov.mot.netmon.audit.AuditResult;
import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.dashboard.DashboardSummaryResponse.Availability;
import gov.mot.netmon.dashboard.DashboardSummaryResponse.Fleet;
import gov.mot.netmon.dashboard.DashboardSummaryResponse.ModuleStatus;
import gov.mot.netmon.metrics.SnapshotStore;
import gov.mot.netmon.agent.AgentProtocol;
import gov.mot.netmon.security.CurrentUser;
import gov.mot.netmon.server.Server;
import gov.mot.netmon.server.ServerEnums.ConnectionStatus;
import gov.mot.netmon.server.ServerGroupRepository;
import gov.mot.netmon.server.ServerRepository;
import gov.mot.netmon.user.RoleCode;
import gov.mot.netmon.user.UserRepository;

@Service
public class DashboardService {

    private final ServerRepository servers;
    private final ServerGroupRepository groups;
    private final UserRepository users;
    private final AuditLogRepository auditLogs;
    private final SnapshotStore snapshots;
    private final gov.mot.netmon.capture.PacketCaptureRepository captures;
    private final Clock clock;
    private final Duration onlineThreshold;
    private final gov.mot.netmon.alert.AlertService alertService;
    private final gov.mot.netmon.alert.AlertRuleRepository alertRules;

    private final gov.mot.netmon.firewall.FirewallIntegrationRepository firewalls;

    public DashboardService(ServerRepository servers, ServerGroupRepository groups, UserRepository users,
            AuditLogRepository auditLogs, SnapshotStore snapshots, gov.mot.netmon.capture.PacketCaptureRepository captures,
            Clock clock, AppProperties props, gov.mot.netmon.alert.AlertService alertService,
            gov.mot.netmon.alert.AlertRuleRepository alertRules, gov.mot.netmon.firewall.FirewallIntegrationRepository firewalls) {
        this.firewalls = firewalls;
        this.alertService = alertService;
        this.alertRules = alertRules;
        this.snapshots = snapshots;
        this.captures = captures;
        this.servers = servers;
        this.groups = groups;
        this.users = users;
        this.auditLogs = auditLogs;
        this.clock = clock;
        this.onlineThreshold = props.monitoring().onlineThreshold();
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse summary() {
        Instant now = clock.instant();
        // Inventory is small (hundreds of servers); counting in memory keeps the query simple.
        List<Server> all = servers.findAll();
        Map<ConnectionStatus, Long> connection = new EnumMap<>(ConnectionStatus.class);
        for (ConnectionStatus c : ConnectionStatus.values()) {
            connection.put(c, 0L);
        }
        Map<String, Long> byStatus = new LinkedHashMap<>();
        Map<String, Long> byOs = new LinkedHashMap<>();
        Map<String, Long> byEnv = new LinkedHashMap<>();
        for (Server s : all) {
            connection.merge(s.connectionStatus(now, onlineThreshold), 1L, Long::sum);
            byStatus.merge(s.getMonitoringStatus().name(), 1L, Long::sum);
            byOs.merge(s.getOsFamily().name(), 1L, Long::sum);
            byEnv.merge(s.getEnvironment().name(), 1L, Long::sum);
        }
        Map<String, Long> connectionOut = new LinkedHashMap<>();
        connection.forEach((k, v) -> connectionOut.put(k.name(), v));

        long activeUsers = users.count((root, q, cb) -> cb.isTrue(root.get("active")));

        var me = CurrentUser.require();
        List<AuditLogResponse> security = null;
        if (me.hasRole(RoleCode.SUPER_ADMIN) || me.hasRole(RoleCode.SECURITY_ANALYST)) {
            Specification<AuditLog> notSuccess = (root, q, cb) -> cb.notEqual(root.get("result"), AuditResult.SUCCESS);
            security = auditLogs.findAll(notSuccess, PageRequest.of(0, 8, Sort.by(Sort.Direction.DESC, "occurredAt")))
                    .map(AuditLogResponse::from).getContent();
        }

        Fleet fleet = fleet(all, now);
        var snaps = snapshots.findAll();
        long captureServers = snaps.values().stream().filter(sn -> sn.report().capture() != null
                && sn.report().capture().enabled() && sn.report().capture().available()).count();
        long active = captures.countByStatusIn(gov.mot.netmon.capture.CaptureStatus.ACTIVE);
        return new DashboardSummaryResponse(now, all.size(), connectionOut, byStatus, byOs, byEnv, groups.count(),
                activeUsers, modules(fleet.reportingServers(), captureServers, alertRules.findByEnabledTrue().size(),
                        firewalls.findAllByOrderByNameAsc()), fleet, active,
                security, alertService.counts());
    }

    private Fleet fleet(List<Server> all, Instant now) {
        var snaps = snapshots.findAll();
        java.util.List<AgentProtocol.Report> live = all.stream()
                .filter(s -> s.connectionStatus(now, onlineThreshold) == ConnectionStatus.ONLINE)
                .map(s -> snaps.get(s.getId()))
                .filter(java.util.Objects::nonNull)
                .filter(sn -> java.time.Duration.between(sn.collectedAt(), now).compareTo(onlineThreshold) <= 0)
                .map(SnapshotStore.Snapshot::report)
                .toList();
        if (live.isEmpty()) {
            return new Fleet(0, null, null, null, null, null, null, null, 0, null);
        }
        var cpu = live.stream().map(r -> r.cpu() == null ? null : r.cpu().usagePercent()).filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).summaryStatistics();
        var mem = live.stream().map(r -> r.memory() == null ? null : r.memory().usedPercent()).filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).summaryStatistics();
        var disk = live.stream().filter(r -> r.disks() != null).flatMap(r -> r.disks().stream())
                .map(AgentProtocol.Disk::usedPercent).filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).max();
        // Loopback traffic never leaves the host, so it is not counted as network throughput.
        var nics = live.stream().filter(r -> r.network() != null).flatMap(r -> r.network().stream())
                .filter(n -> !"lo".equals(n.name())).toList();
        Double rx = nics.stream().anyMatch(n -> n.rxBitsPerSec() != null)
                ? nics.stream().map(AgentProtocol.NetworkInterface::rxBitsPerSec).filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).sum() : null;
        Double tx = nics.stream().anyMatch(n -> n.txBitsPerSec() != null)
                ? nics.stream().map(AgentProtocol.NetworkInterface::txBitsPerSec).filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).sum() : null;
        int errors = live.stream().filter(r -> r.collectors() != null).mapToInt(r -> (int) r.collectors().stream()
                .filter(c -> !"OK".equals(c.status())).count()).sum();
        Instant oldest = live.stream().map(AgentProtocol.Report::collectedAt).min(Instant::compareTo).orElse(null);
        return new Fleet(live.size(),
                cpu.getCount() == 0 ? null : cpu.getAverage(), cpu.getCount() == 0 ? null : cpu.getMax(),
                mem.getCount() == 0 ? null : mem.getAverage(), mem.getCount() == 0 ? null : mem.getMax(),
                disk.isPresent() ? disk.getAsDouble() : null, rx, tx, errors, oldest);
    }

    /** Honest status of each telemetry module in this release. Updated as phases are delivered. */
    private static List<ModuleStatus> modules(int reportingServers, long captureServers, int enabledRules,
            List<gov.mot.netmon.firewall.FirewallIntegration> fw) {
        long enforce = fw.stream().filter(f -> "ENFORCE".equals(f.getMode())).count();
        ModuleStatus metrics = reportingServers > 0
                ? new ModuleStatus("metrics", "System metrics (CPU, memory, disk, network)", Availability.AVAILABLE,
                        reportingServers + " server(s) reporting live data.")
                : new ModuleStatus("metrics", "System metrics (CPU, memory, disk, network)", Availability.NOT_COLLECTED,
                        "No agent is reporting. Enroll a Linux or Windows agent from a server's page (Agent tab).");
        return List.of(
                metrics,
                captureServers > 0
                        ? new ModuleStatus("capture", "Packet capture", Availability.AVAILABLE,
                                captureServers + " server(s) can capture packets.")
                        : new ModuleStatus("capture", "Packet capture", Availability.NOT_CONFIGURED,
                                "No server has packet capture enabled in its agent configuration."),
                enabledRules > 0
                        ? new ModuleStatus("alerts", "Alerts and event timeline", Availability.AVAILABLE,
                                enabledRules + " alert rule(s) enabled.")
                        : new ModuleStatus("alerts", "Alerts and event timeline", Availability.NOT_CONFIGURED,
                                "All alert rules are disabled. Events are still recorded on the timeline."),
                fw.isEmpty()
                        ? new ModuleStatus("firewall", "Firewall integrations", Availability.NOT_CONFIGURED,
                                "No firewall integration is configured.")
                        : new ModuleStatus("firewall", "Firewall integrations", Availability.AVAILABLE,
                                fw.size() + " firewall target(s): " + (fw.size() - enforce) + " in dry run, " + enforce + " set to enforce."));
    }
}
