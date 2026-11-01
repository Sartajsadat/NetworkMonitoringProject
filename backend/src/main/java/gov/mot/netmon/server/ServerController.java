package gov.mot.netmon.server;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import gov.mot.netmon.common.PageResponse;
import gov.mot.netmon.server.ServerDtos.GroupRequest;
import gov.mot.netmon.server.ServerDtos.GroupResponse;
import gov.mot.netmon.server.ServerDtos.ServerRequest;
import gov.mot.netmon.server.ServerDtos.ServerResponse;
import gov.mot.netmon.server.ServerEnums.Environment;
import gov.mot.netmon.server.ServerEnums.MonitoringStatus;
import gov.mot.netmon.server.ServerEnums.OsFamily;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Server inventory")
public class ServerController {

    private static final String CAN_MANAGE = "hasAnyRole('SUPER_ADMIN','INFRASTRUCTURE_ADMIN')";

    private final ServerService servers;
    private final ServerGroupService groups;

    public ServerController(ServerService servers, ServerGroupService groups) {
        this.servers = servers;
        this.groups = groups;
    }

    @GetMapping("/servers")
    @Operation(summary = "Search registered servers")
    public PageResponse<ServerResponse> search(@RequestParam(required = false) String search,
            @RequestParam(required = false) Environment environment,
            @RequestParam(required = false) OsFamily osFamily,
            @RequestParam(required = false) String organization,
            @RequestParam(required = false) MonitoringStatus monitoringStatus,
            @RequestParam(required = false) UUID groupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort) {
        return servers.search(new ServerService.Filter(search, environment, osFamily, organization, monitoringStatus,
                groupId), page, size, sort);
    }

    @GetMapping("/servers/organizations")
    @Operation(summary = "Distinct owning organizations (for filters)")
    public List<String> organizations() {
        return servers.organizations();
    }

    @GetMapping("/servers/{id}")
    public ServerResponse get(@PathVariable UUID id) {
        return servers.get(id);
    }

    @PostMapping("/servers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CAN_MANAGE)
    @Operation(summary = "Register a server in the inventory")
    public ServerResponse create(@Valid @RequestBody ServerRequest req) {
        return servers.create(req);
    }

    @PutMapping("/servers/{id}")
    @PreAuthorize(CAN_MANAGE)
    public ServerResponse update(@PathVariable UUID id, @Valid @RequestBody ServerRequest req) {
        return servers.update(id, req);
    }

    @DeleteMapping("/servers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Remove a server from the inventory (prefer status DECOMMISSIONED to keep history)")
    public void delete(@PathVariable UUID id) {
        servers.delete(id);
    }

    @GetMapping("/server-groups")
    public List<GroupResponse> listGroups() {
        return groups.list();
    }

    @PostMapping("/server-groups")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CAN_MANAGE)
    public GroupResponse createGroup(@Valid @RequestBody GroupRequest req) {
        return groups.create(req);
    }

    @PutMapping("/server-groups/{id}")
    @PreAuthorize(CAN_MANAGE)
    public GroupResponse updateGroup(@PathVariable UUID id, @Valid @RequestBody GroupRequest req) {
        return groups.update(id, req);
    }

    @DeleteMapping("/server-groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(CAN_MANAGE)
    public void deleteGroup(@PathVariable UUID id) {
        groups.delete(id);
    }
}
