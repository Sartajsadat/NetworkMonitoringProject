package gov.mot.netmon.server;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import gov.mot.netmon.server.ServerEnums.AgentHealth;
import gov.mot.netmon.server.ServerEnums.ConnectionStatus;
import gov.mot.netmon.server.ServerEnums.Environment;
import gov.mot.netmon.server.ServerEnums.GroupType;
import gov.mot.netmon.server.ServerEnums.MonitoringStatus;
import gov.mot.netmon.server.ServerEnums.OsFamily;

public final class ServerDtos {

    private ServerDtos() {
    }

    public record IpAddressDto(
            @NotBlank @Size(max = 45) String address,
            @Size(max = 60) String label,
            Integer version) {
    }

    public record ServerRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 253) String hostname,
            @NotNull OsFamily osFamily,
            @Size(max = 120) String osVersion,
            @NotNull Environment environment,
            @NotBlank @Size(max = 150) String organization,
            @Size(max = 500) String purpose,
            @Size(max = 120) String location,
            MonitoringStatus monitoringStatus,
            @Size(max = 2000) String notes,
            @Size(max = 32) List<@Valid IpAddressDto> ipAddresses,
            @Size(max = 50) Set<UUID> groupIds) {
    }

    public record GroupRef(UUID id, String name, GroupType groupType) {
        static GroupRef from(ServerGroup g) {
            return new GroupRef(g.getId(), g.getName(), g.getGroupType());
        }
    }

    public record ServerResponse(
            UUID id,
            String name,
            String hostname,
            OsFamily osFamily,
            String osVersion,
            Environment environment,
            String organization,
            String purpose,
            String location,
            MonitoringStatus monitoringStatus,
            ConnectionStatus connectionStatus,
            AgentHealth agentHealth,
            String agentVersion,
            Instant lastSeenAt,
            String notes,
            List<IpAddressDto> ipAddresses,
            List<GroupRef> groups,
            Instant createdAt,
            Instant updatedAt,
            String createdBy,
            String updatedBy) {

        static ServerResponse from(Server s, ConnectionStatus connection) {
            return new ServerResponse(s.getId(), s.getName(), s.getHostname(), s.getOsFamily(), s.getOsVersion(),
                    s.getEnvironment(), s.getOrganization(), s.getPurpose(), s.getLocation(),
                    s.getMonitoringStatus(), connection, s.getAgentHealth(), s.getAgentVersion(), s.getLastSeenAt(),
                    s.getNotes(),
                    s.getIpAddresses().stream()
                            .map(ip -> new IpAddressDto(ip.getIpAddress(), ip.getLabel(), ip.getIpVersion()))
                            .toList(),
                    s.getGroups().stream().sorted(Comparator.comparing(ServerGroup::getName)).map(GroupRef::from)
                            .toList(),
                    s.getCreatedAt(), s.getUpdatedAt(), s.getCreatedBy(), s.getUpdatedBy());
        }
    }

    public record GroupRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull GroupType groupType,
            @Size(max = 500) String description) {
    }

    public record GroupResponse(UUID id, String name, GroupType groupType, String description, long memberCount,
            Instant createdAt, Instant updatedAt) {
    }
}
