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
import gov.mot.netmon.dashboard.DashboardSummaryResponse.ModuleStatus;
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
    private final Clock clock;
    private final Duration onlineThreshold;

    public DashboardService(ServerRepository servers, ServerGroupRepository groups, UserRepository users,
            AuditLogRepository auditLogs, Clock clock, AppProperties props) {
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

        return new DashboardSummaryResponse(now, all.size(), connectionOut, byStatus, byOs, byEnv, groups.count(),
                activeUsers, modules(), security);
    }

    /** Honest status of each telemetry module in this release. Updated as phases are delivered. */
    private static List<ModuleStatus> modules() {
        return List.of(
                new ModuleStatus("metrics", "System metrics (CPU, memory, disk, network)", Availability.NOT_COLLECTED,
                        "No monitoring agent is installed yet. Agents arrive in Phase 2 (Linux) and Phase 3 (Windows)."),
                new ModuleStatus("capture", "Packet capture", Availability.NOT_CONFIGURED,
                        "Packet capture integration (dumpcap/TShark) arrives in Phase 4."),
                new ModuleStatus("alerts", "Alerts and event timeline", Availability.NOT_CONFIGURED,
                        "Alert rules and event correlation arrive in Phase 5."),
                new ModuleStatus("firewall", "Firewall integrations", Availability.NOT_CONFIGURED,
                        "No firewall integration is configured. Dry-run adapter arrives in Phase 7."));
    }
}
