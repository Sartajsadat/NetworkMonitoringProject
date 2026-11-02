package gov.mot.netmon.ops;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import gov.mot.netmon.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Retention and storage management, and the platform's own health. */
@RestController
@Tag(name = "Operations")
public class OpsController {

    public record RetentionUpdate(int retentionDays, @NotBlank @Size(min = 5, max = 500) String reason) {
    }

    private final RetentionService retention;
    private final SystemHealthService health;

    public OpsController(RetentionService retention, SystemHealthService health) {
        this.retention = retention;
        this.health = health;
    }

    @GetMapping("/api/v1/retention-policies")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','INFRASTRUCTURE_ADMIN')")
    public List<RetentionService.Policy> policies() {
        return retention.list();
    }

    @PutMapping("/api/v1/retention-policies/{type}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public RetentionService.Policy update(@PathVariable String type, @Valid @RequestBody RetentionUpdate r) {
        return retention.update(type, r.retentionDays(), r.reason());
    }

    @PostMapping("/api/v1/retention-policies/run")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Apply every retention policy now")
    public List<RetentionService.RunResult> run() {
        return retention.runAll(CurrentUser.require().username());
    }

    @GetMapping("/api/v1/system/health")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','INFRASTRUCTURE_ADMIN')")
    @Operation(summary = "Health of the monitoring platform itself: database, storage, jobs, agents, configuration")
    public SystemHealthService.Health health() {
        return health.health();
    }
}
