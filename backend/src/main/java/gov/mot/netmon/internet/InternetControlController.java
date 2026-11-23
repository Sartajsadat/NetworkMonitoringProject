package gov.mot.netmon.internet;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import gov.mot.netmon.internet.InternetDtos.AppInternet;
import gov.mot.netmon.internet.InternetDtos.AppRequest;
import gov.mot.netmon.internet.InternetDtos.Computer;
import gov.mot.netmon.internet.InternetDtos.InternetView;
import gov.mot.netmon.internet.InternetDtos.SiteRequest;
import gov.mot.netmon.internet.InternetDtos.SiteSwitch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** On/off switches for websites and applications' internet access, per computer. */
@RestController
@Tag(name = "Internet control")
@PreAuthorize(InternetControlController.MANAGE)
public class InternetControlController {

    static final String MANAGE = "hasAnyRole('SUPER_ADMIN','NETWORK_ADMIN')";

    private final InternetControlService service;

    public InternetControlController(InternetControlService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/internet-control")
    public List<Computer> computers() {
        return service.computers();
    }

    @GetMapping("/api/v1/internet-control/{serverId}")
    public InternetView view(@PathVariable UUID serverId) {
        return service.view(serverId);
    }

    @PostMapping("/api/v1/internet-control/{serverId}/sites")
    @Operation(summary = "Block a website on this computer (by name, through its hosts file)")
    public InternetView addSite(@PathVariable UUID serverId, @Valid @RequestBody SiteRequest r) {
        return service.addSite(serverId, r.site());
    }

    @PutMapping("/api/v1/internet-control/sites/{id}")
    public InternetView switchSite(@PathVariable UUID id, @Valid @RequestBody SiteSwitch r) {
        return service.switchSite(id, r.blocked());
    }

    @DeleteMapping("/api/v1/internet-control/sites/{id}")
    public InternetView removeSite(@PathVariable UUID id) {
        return service.removeSite(id);
    }

    @PostMapping("/api/v1/internet-control/{serverId}/apps")
    @Operation(summary = "Add an application (Windows program path or Linux account) to the computer's switches")
    public InternetView addApp(@PathVariable UUID serverId, @Valid @RequestBody AppRequest r) {
        return service.addApp(serverId, r.name(), r.identity());
    }

    @PutMapping("/api/v1/internet-control/apps/{id}")
    @Operation(summary = "Switch an application's internet access on or off")
    public InternetView switchApp(@PathVariable UUID id, @Valid @RequestBody AppInternet r) {
        return service.switchApp(id, r.internet());
    }
}
