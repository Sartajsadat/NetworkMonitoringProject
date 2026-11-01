package gov.mot.netmon.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import gov.mot.netmon.audit.AuditLogResponse;

/**
 * Dashboard summary. Each module reports an availability status so the UI can show
 * "not collected yet" instead of zeros that look like real measurements.
 */
public record DashboardSummaryResponse(
        Instant generatedAt,
        long totalServers,
        Map<String, Long> connection,
        Map<String, Long> byMonitoringStatus,
        Map<String, Long> byOsFamily,
        Map<String, Long> byEnvironment,
        long serverGroups,
        long activeUsers,
        List<ModuleStatus> modules,
        List<AuditLogResponse> recentSecurityEvents) {

    public enum Availability { AVAILABLE, NOT_COLLECTED, NOT_CONFIGURED, UNSUPPORTED }

    public record ModuleStatus(String module, Availability availability, String message) {
    }
}
