package gov.mot.netmon.firewall;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class FirewallDtos {

    private FirewallDtos() {
    }

    /** What the target reports about its firewall (from the latest agent report). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TargetState(boolean agentFirewallEnabled, boolean enforcementAllowedOnServer, String backend, String message,
            List<String> protectedAddresses, Instant reportedAt, boolean online) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IntegrationView(UUID id, String name, String kind, UUID serverId, String serverName, String environment,
            String mode, String description, String modeChangedBy, Instant modeChangedAt, TargetState target, long activeRules,
            boolean enforcing) {
    }

    public record IntegrationRequest(UUID serverId, @Size(max = 120) String name, @Size(max = 500) String description) {
    }

    public record ModeRequest(@NotBlank String mode, @NotBlank @Size(min = 5, max = 500) String reason) {
    }

    public record RuleRequest(
            @jakarta.validation.constraints.NotNull UUID integrationId,
            @NotBlank String action,
            @NotBlank String direction,
            @NotBlank @Size(max = 50) String remote,
            @Size(max = 50) String local,
            @Size(max = 5) String protocol,
            @Size(max = 11) String localPort,
            @Size(max = 11) String remotePort,
            @NotBlank @Size(min = 10, max = 500) String reason,
            @Size(max = 120) String reference,
            Instant expiresAt,
            boolean acknowledgeWarnings) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Preview(boolean ok, List<RuleValidator.Issue> errors, List<RuleValidator.Issue> warnings, String scope,
            String scopeKind, String targetName, String command, String mode, String backend) {
    }

    public record DecisionRequest(@Size(max = 1000) String comment) {
    }

    public record RemovalRequest(@NotBlank @Size(min = 5, max = 500) String reason) {
    }

    public record ApprovalView(String decision, String decidedBy, Instant decidedAt, String comment) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuleView(UUID id, UUID integrationId, String integrationName, String integrationKind, UUID serverId,
            String serverName, String scope, String tag, String action, String direction, String remoteCidr, String localCidr,
            String protocol, String localPort, String remotePort, String reason, String reference, Instant expiresAt,
            String status, String statusMessage, String modeAtApply, String preview, List<String> warnings, String agentCommand,
            String requestedBy, Instant requestedAt, String decidedBy, Instant decidedAt, Instant appliedAt,
            Instant lastVerifiedAt, String removalRequestedBy, Instant removalRequestedAt, String removalReason, Instant removedAt,
            boolean expiring) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuleDetail(RuleView rule, List<ApprovalView> approvals) {
    }

    /** Sent by the agent after applying, checking or removing a rule. */
    public record AgentRuleStatus(@NotBlank @Size(max = 20) String state, @Size(max = 1000) String message,
            @Size(max = 2000) String command) {
    }
}
