package gov.mot.netmon.audit;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import gov.mot.netmon.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "Audit logs")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','SECURITY_ANALYST')")
public class AuditController {

    private final AuditQueryService queryService;

    public AuditController(AuditQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @Operation(summary = "Search the audit trail (newest first)")
    public PageResponse<AuditLogResponse> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) AuditResult result,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sort) {
        return queryService.search(new AuditQueryService.Filter(from, to, actor, action, result, targetType, targetId),
                page, size, sort);
    }

    @GetMapping("/actions")
    @Operation(summary = "List all audit action codes (for filter drop-downs)")
    public List<String> actions() {
        return Arrays.stream(AuditAction.values()).map(Enum::name).toList();
    }
}
