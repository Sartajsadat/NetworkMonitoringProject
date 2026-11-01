package gov.mot.netmon.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import gov.mot.netmon.server.ServerEnums.ConnectionStatus;
import gov.mot.netmon.server.ServerEnums.OsFamily;

/** DTOs for the admin-facing agent API (the agent-facing wire format is in {@link AgentProtocol}). */
public final class AgentDtos {

    private AgentDtos() {
    }

    /**
     * Service names: systemd units on Linux (nginx.service, postgresql@16-main.service) and Windows
     * service names (W3SVC, MSSQL$SQLEXPRESS). No spaces, quotes or separators.
     */
    public static final String UNIT_PATTERN = "^[A-Za-z0-9@._:$-]{1,128}$";

    public record EnrollmentTokenResponse(String token, Instant expiresAt, UUID serverId, String serverName,
            String platformUrl) {
        @Override
        public String toString() {
            return "EnrollmentTokenResponse[serverId=" + serverId + "]";
        }
    }

    public record ConfigView(int reportIntervalSeconds, List<String> services, Instant updatedAt, String updatedBy) {
    }

    public record ConfigRequest(
            @NotNull @Min(10) @Max(3600) Integer reportIntervalSeconds,
            @NotNull @Size(max = 50) List<@Pattern(regexp = UNIT_PATTERN,
                    message = "must be a service name such as nginx.service or W3SVC") String> services) {
    }

    /** status: ACTIVE, REVOKED or NOT_ENROLLED. */
    public record AgentView(
            UUID serverId,
            String serverName,
            String serverHostname,
            OsFamily serverOsFamily,
            UUID agentId,
            String status,
            ConnectionStatus connectionStatus,
            String agentVersion,
            String platform,
            String osName,
            String osVersion,
            String kernel,
            String architecture,
            String reportedHostname,
            boolean hostnameMismatch,
            boolean platformMismatch,
            Instant enrolledAt,
            String enrolledFromIp,
            Instant lastSeenAt,
            String lastReportIp,
            int lastErrorCount,
            Instant credentialRotatedAt,
            Instant revokedAt,
            String revokedBy,
            Instant pendingTokenExpiresAt,
            ConfigView config) {
    }
}
