package gov.mot.netmon.website;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import gov.mot.netmon.website.WebsiteDtos.Overview;
import gov.mot.netmon.website.WebsiteDtos.SiteRequest;
import gov.mot.netmon.website.WebsiteDtos.SwitchRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/websites")
@Tag(name = "Website blocking")
public class WebsiteController {

    static final String MANAGE = "hasAnyRole('SUPER_ADMIN','NETWORK_ADMIN')";

    private final WebsiteService service;

    public WebsiteController(WebsiteService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Websites with their switch, and what every device confirmed")
    public Overview overview() {
        return service.overview();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGE)
    @Operation(summary = "Add a website; it is blocked on every device with website blocking enabled")
    public Overview add(@Valid @RequestBody SiteRequest r) {
        return service.add(r.website(), r.note());
    }

    @PutMapping("/{id}/blocked")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Turn blocking of this website on or off")
    public Overview setBlocked(@PathVariable UUID id, @RequestBody SwitchRequest r) {
        return service.setBlocked(id, r.blocked());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(MANAGE)
    public Overview delete(@PathVariable UUID id) {
        return service.delete(id);
    }
}
