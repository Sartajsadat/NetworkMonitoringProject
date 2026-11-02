package gov.mot.netmon.firewall;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AppDtos {

    private AppDtos() {
    }

    public record AppRequest(@NotNull UUID serverId, @NotBlank @Size(max = 120) String name, @Size(max = 500) String description,
            @Size(max = 120) String owner, @NotBlank String identityKind, @NotBlank @Size(max = 260) String identityValue) {
    }

    /** Whether the server's firewall can enforce this application's identity, and why not. */
    public record Support(boolean supported, String reason, UUID integrationId, String mode, boolean enforcing) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AppView(UUID id, UUID serverId, String serverName, String osFamily, String name, String description, String owner,
            String identityKind, String identityValue, Support support, long activePolicies, List<String> seenProcesses,
            String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
    }

    public record PolicyRequest(@NotNull UUID applicationId, @NotBlank String appMode,
            @Size(max = 50) List<AppPolicyValidator.Destination> destinations,
            @NotBlank @Size(min = 10, max = 500) String reason, @Size(max = 120) String reference, Instant expiresAt,
            boolean acknowledgeWarnings) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PolicyPreview(boolean ok, List<RuleValidator.Issue> errors, List<RuleValidator.Issue> warnings, String scope,
            String targetName, String command, String mode, String backend, String unsupported) {
    }
}
