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
        Fleet fleet,
        List<AuditLogResponse> recentSecurityEvents) {

    /**
     * Aggregates over servers whose agent reported within the online threshold. All values are null
     * when no server is reporting (never zero, which would look like a real measurement).
     */
    public record Fleet(int reportingServers, Double avgCpuPercent, Double maxCpuPercent, Double avgMemoryPercent,
            Double maxMemoryPercent, Double maxDiskUsedPercent, Double totalRxBitsPerSec, Double totalTxBitsPerSec,
            int collectorErrors, java.time.Instant oldestDataAt) {
    }

    public enum Availability { AVAILABLE, NOT_COLLECTED, NOT_CONFIGURED, UNSUPPORTED }

    /** {@code key} is a stable identifier the UI uses to translate the module name and message. */
    public record ModuleStatus(String key, String module, Availability availability, String message) {
    }
}
