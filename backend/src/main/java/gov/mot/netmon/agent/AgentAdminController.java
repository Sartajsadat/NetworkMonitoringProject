package gov.mot.netmon.agent;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import gov.mot.netmon.agent.AgentDtos.AgentView;
import gov.mot.netmon.agent.AgentDtos.ConfigRequest;
import gov.mot.netmon.agent.AgentDtos.EnrollmentTokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Agents (administration)")
public class AgentAdminController {

    private static final String CAN_MANAGE = "hasAnyRole('SUPER_ADMIN','INFRASTRUCTURE_ADMIN')";

    private final AgentAdminService admin;
    private final AgentEnrollmentService enrollment;

    public AgentAdminController(AgentAdminService admin, AgentEnrollmentService enrollment) {
        this.admin = admin;
        this.enrollment = enrollment;
    }

    @GetMapping("/agents")
    @Operation(summary = "Agent state of every registered server")
    public List<AgentView> list() {
        return admin.list();
    }

    @GetMapping("/servers/{id}/agent")
    public AgentView forServer(@PathVariable UUID id) {
        return admin.forServer(id);
    }

    @PostMapping("/servers/{id}/agent/enrollment-token")
    @PreAuthorize(CAN_MANAGE)
    @Operation(summary = "Create a one-time enrollment token (shown once; earlier unused tokens stop working)")
    public ResponseEntity<EnrollmentTokenResponse> createToken(@PathVariable UUID id) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(enrollment.createToken(id));
    }

    @PostMapping("/servers/{id}/agent/revoke")
    @PreAuthorize(CAN_MANAGE)
    @Operation(summary = "Revoke the server's agent credential immediately")
    public AgentView revoke(@PathVariable UUID id) {
        return admin.revoke(id);
    }

    @PutMapping("/servers/{id}/agent/config")
    @PreAuthorize(CAN_MANAGE)
    @Operation(summary = "Set report interval and the systemd services the agent reports on")
    public AgentView updateConfig(@PathVariable UUID id, @Valid @RequestBody ConfigRequest req) {
        return admin.updateConfig(id, req);
    }
}
