package gov.mot.netmon.firewall;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import gov.mot.netmon.firewall.AppDtos.AppRequest;
import gov.mot.netmon.firewall.AppDtos.AppView;
import gov.mot.netmon.firewall.AppDtos.PolicyPreview;
import gov.mot.netmon.firewall.AppDtos.PolicyRequest;
import gov.mot.netmon.firewall.FirewallDtos.RuleView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Application inventory and application access policies. Approval, removal etc. use /api/v1/firewall-rules. */
@RestController
@Tag(name = "Application access control")
public class ApplicationController {

    private final ApplicationService service;

    public ApplicationController(ApplicationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/applications")
    public List<AppView> list(@RequestParam(required = false) UUID serverId) {
        return service.list(serverId);
    }

    @GetMapping("/api/v1/applications/{id}")
    public AppView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/api/v1/applications")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(FirewallController.MANAGE)
    public AppView create(@Valid @RequestBody AppRequest r) {
        return service.create(r);
    }

    @PutMapping("/api/v1/applications/{id}")
    @PreAuthorize(FirewallController.MANAGE)
    public AppView update(@PathVariable UUID id, @Valid @RequestBody AppRequest r) {
        return service.update(id, r);
    }

    @DeleteMapping("/api/v1/applications/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(FirewallController.MANAGE)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/api/v1/application-policies/preview")
    @PreAuthorize(FirewallController.REQUEST)
    @Operation(summary = "Validate an application policy and show the exact change and whether the server can enforce it")
    public PolicyPreview preview(@Valid @RequestBody PolicyRequest r) {
        return service.preview(r);
    }

    @PostMapping("/api/v1/application-policies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(FirewallController.REQUEST)
    @Operation(summary = "Request an application policy; it is then approved and removed like any firewall rule")
    public RuleView request(@Valid @RequestBody PolicyRequest r) {
        return service.request(r);
    }
}
