package gov.mot.netmon.alert;

import java.util.List;
import java.util.UUID;

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

import gov.mot.netmon.alert.AlertDtos.AckRequest;
import gov.mot.netmon.alert.AlertDtos.AlertCounts;
import gov.mot.netmon.alert.AlertDtos.AlertDetail;
import gov.mot.netmon.alert.AlertDtos.AlertView;
import gov.mot.netmon.alert.AlertDtos.ResolveRequest;
import gov.mot.netmon.alert.AlertDtos.RuleRequest;
import gov.mot.netmon.alert.AlertDtos.RuleTypeInfo;
import gov.mot.netmon.alert.AlertDtos.RuleView;
import gov.mot.netmon.common.PageResponse;
import gov.mot.netmon.event.EventSeverity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Alerts are visible to every signed-in user (security/capture alerts only to their roles).
 * Acknowledging and resolving needs an operator role; rules are managed by super and
 * infrastructure administrators.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Alerts")
public class AlertController {

    static final String OPERATE = "hasAnyRole('SUPER_ADMIN','INFRASTRUCTURE_ADMIN','NETWORK_ADMIN','SECURITY_ANALYST')";
    static final String MANAGE_RULES = "hasAnyRole('SUPER_ADMIN','INFRASTRUCTURE_ADMIN')";

    private final AlertService alerts;
    private final AlertRuleService rules;

    public AlertController(AlertService alerts, AlertRuleService rules) {
        this.alerts = alerts;
        this.rules = rules;
    }

    @GetMapping("/alerts")
    @Operation(summary = "Alerts; status ACTIVE (default), OPEN, ACKNOWLEDGED, RESOLVED or ALL")
    public PageResponse<AlertView> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) EventSeverity severity, @RequestParam(required = false) UUID serverId,
            @RequestParam(required = false) UUID ruleId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return alerts.list(status, severity, serverId, ruleId, page, size);
    }

    @GetMapping("/alerts/counts")
    public AlertCounts counts() {
        return alerts.counts();
    }

    @GetMapping("/alerts/{id}")
    public AlertDetail get(@PathVariable UUID id) {
        return alerts.get(id);
    }

    @PostMapping("/alerts/{id}/acknowledge")
    @PreAuthorize(OPERATE)
    public AlertView acknowledge(@PathVariable UUID id, @Valid @RequestBody(required = false) AckRequest req) {
        return alerts.acknowledge(id, req == null ? null : req.comment());
    }

    @PostMapping("/alerts/{id}/resolve")
    @PreAuthorize(OPERATE)
    @Operation(summary = "Close an alert by hand (it reopens after the cooldown if the condition still holds)")
    public AlertView resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest req) {
        return alerts.resolve(id, req.comment());
    }

    @GetMapping("/alert-rules")
    public List<RuleView> rules() {
        return rules.list();
    }

    @GetMapping("/alert-rules/types")
    public List<RuleTypeInfo> types() {
        return rules.types();
    }

    @PostMapping("/alert-rules")
    @PreAuthorize(MANAGE_RULES)
    @ResponseStatus(HttpStatus.CREATED)
    public RuleView create(@Valid @RequestBody RuleRequest req) {
        return rules.create(req);
    }

    @PutMapping("/alert-rules/{id}")
    @PreAuthorize(MANAGE_RULES)
    public RuleView update(@PathVariable UUID id, @Valid @RequestBody RuleRequest req) {
        return rules.update(id, req);
    }

    @DeleteMapping("/alert-rules/{id}")
    @PreAuthorize(MANAGE_RULES)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        rules.delete(id);
    }
}
