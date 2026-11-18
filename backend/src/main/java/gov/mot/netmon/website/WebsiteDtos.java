package gov.mot.netmon.website;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class WebsiteDtos {

    private WebsiteDtos() {
    }

    public record SiteRequest(@NotBlank @Size(max = 300) String website, @Size(max = 200) String note) {
    }

    public record SwitchRequest(boolean blocked) {
    }

    /**
     * One device's state for one website: BLOCKED / ALLOWED (what the device confirmed), WAITING (the
     * device has not confirmed the change yet), OFFLINE (not confirmed and the device is not reporting),
     * NOT_SET_UP (website blocking is not enabled on that device, or its agent is too old).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeviceStatus(UUID serverId, String serverName, String state) {
    }

    public record Summary(int total, int done, int waiting, int offline, int notSetUp) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SiteView(UUID id, String domain, List<String> names, boolean blocked, String note, String createdBy, Instant createdAt,
            String updatedBy, Instant updatedAt, Summary summary, List<DeviceStatus> devices) {
    }

    /** A device (server or staff PC with an agent) and whether website blocking is set up on it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeviceView(UUID serverId, String name, String osFamily, boolean enabled, boolean online, String message,
            Instant reportedAt) {
    }

    public record Overview(List<SiteView> sites, List<DeviceView> devices) {
    }
}
