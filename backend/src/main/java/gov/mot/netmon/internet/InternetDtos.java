package gov.mot.netmon.internet;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class InternetDtos {

    private InternetDtos() {
    }

    /** A computer that can be controlled (it has an agent). */
    public record Computer(UUID id, String name, String osFamily, String connection) {
    }

    /**
     * Website state on the computer: BLOCKED (read back from the hosts file), ALLOWED, BLOCKING /
     * UNBLOCKING (waiting for the computer's next report), or OFF_ON_COMPUTER (sites.enabled is not set).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SiteView(UUID id, String domain, boolean blocked, String state, String updatedBy, Instant updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Sites(boolean enabledOnComputer, String message, List<SiteView> list) {
    }

    /**
     * One application's internet switch. {@code internetBlocked}: a "block internet" policy is active;
     * {@code status}: that policy's status (APPLIED, APPROVED, REQUESTED, DRY_RUN, REMOVAL_PENDING,
     * FAILED, ...) and {@code message} why.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AppSwitch(UUID id, String name, String identityKind, String identityValue, boolean internetBlocked,
            String status, String message, UUID ruleId, List<String> seenProcesses) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Apps(boolean supported, String message, String identityKind, boolean enforcing, List<AppSwitch> list) {
    }

    public record InternetView(UUID serverId, String serverName, String osFamily, String connection, boolean personalMode,
            Sites sites, Apps apps) {
    }

    public record SiteRequest(@NotBlank @Size(max = 2000) String site) {
    }

    public record SiteSwitch(@NotNull Boolean blocked) {
    }

    /** {@code identity}: full path of the program (Windows) or the account name (Linux). */
    public record AppRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 260) String identity) {
    }

    public record AppInternet(@NotNull Boolean internet) {
    }
}
