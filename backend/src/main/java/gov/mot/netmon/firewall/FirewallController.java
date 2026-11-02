package gov.mot.netmon.firewall;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import gov.mot.netmon.agent.AgentPrincipal;
import gov.mot.netmon.firewall.FirewallDtos.AgentRuleStatus;
import gov.mot.netmon.firewall.FirewallDtos.DecisionRequest;
import gov.mot.netmon.firewall.FirewallDtos.IntegrationRequest;
import gov.mot.netmon.firewall.FirewallDtos.IntegrationView;
import gov.mot.netmon.firewall.FirewallDtos.ModeRequest;
import gov.mot.netmon.firewall.FirewallDtos.Preview;
import gov.mot.netmon.firewall.FirewallDtos.RemovalRequest;
import gov.mot.netmon.firewall.FirewallDtos.RuleDetail;
import gov.mot.netmon.firewall.FirewallDtos.RuleRequest;
import gov.mot.netmon.firewall.FirewallDtos.RuleView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Firewall and access control")
public class FirewallController {

    static final String MANAGE = "hasAnyRole('SUPER_ADMIN','NETWORK_ADMIN')";
    static final String REQUEST = "hasAnyRole('SUPER_ADMIN','NETWORK_ADMIN','SECURITY_ANALYST')";

    private final FirewallService firewalls;
    private final FirewallRuleService rules;

    public FirewallController(FirewallService firewalls, FirewallRuleService rules) {
        this.firewalls = firewalls;
        this.rules = rules;
    }

    // ---- integrations (enforcement points)

    @GetMapping("/api/v1/firewall-integrations")
    public List<IntegrationView> integrations() {
        return firewalls.list();
    }

    @GetMapping("/api/v1/firewall-integrations/{id}")
    public IntegrationView integration(@PathVariable UUID id) {
        return firewalls.get(id);
    }

    @PostMapping("/api/v1/firewall-integrations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGE)
    @Operation(summary = "Add a server's host firewall (serverId) or a central firewall (name; dry run only)")
    public IntegrationView create(@Valid @RequestBody IntegrationRequest r) {
        return firewalls.create(r);
    }

    @PutMapping("/api/v1/firewall-integrations/{id}/mode")
    @PreAuthorize(MANAGE)
    @Operation(summary = "DRY_RUN or ENFORCE (ENFORCE: super administrator, and only if the server allows it)")
    public IntegrationView mode(@PathVariable UUID id, @Valid @RequestBody ModeRequest r) {
        return firewalls.setMode(id, r.mode(), r.reason());
    }

    @DeleteMapping("/api/v1/firewall-integrations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(MANAGE)
    public void delete(@PathVariable UUID id) {
        firewalls.delete(id);
    }

    // ---- rules

    @GetMapping("/api/v1/firewall-rules")
    public Page<RuleView> list(@RequestParam(required = false) UUID integrationId, @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String address, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return rules.list(integrationId, status, address, page, size);
    }

    @GetMapping("/api/v1/firewall-rules/{id}")
    public RuleDetail get(@PathVariable UUID id) {
        return rules.get(id);
    }

    @PostMapping("/api/v1/firewall-rules/preview")
    @PreAuthorize(REQUEST)
    @Operation(summary = "Validate a rule and show the exact change, without saving anything")
    public Preview preview(@Valid @RequestBody RuleRequest r) {
        return rules.preview(r);
    }

    @PostMapping("/api/v1/firewall-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(REQUEST)
    public RuleView request(@Valid @RequestBody RuleRequest r) {
        return rules.request(r);
    }

    @PostMapping("/api/v1/firewall-rules/{id}/approve")
    @PreAuthorize(MANAGE)
    public RuleView approve(@PathVariable UUID id, @Valid @RequestBody(required = false) DecisionRequest r) {
        return rules.approve(id, r == null ? null : r.comment());
    }

    @PostMapping("/api/v1/firewall-rules/{id}/reject")
    @PreAuthorize(MANAGE)
    public RuleView reject(@PathVariable UUID id, @Valid @RequestBody DecisionRequest r) {
        return rules.reject(id, r.comment());
    }

    @PostMapping("/api/v1/firewall-rules/{id}/cancel")
    @PreAuthorize(REQUEST)
    public RuleView cancel(@PathVariable UUID id) {
        return rules.cancel(id);
    }

    @PostMapping("/api/v1/firewall-rules/{id}/remove")
    @PreAuthorize(MANAGE)
    public RuleView remove(@PathVariable UUID id, @Valid @RequestBody RemovalRequest r) {
        return rules.remove(id, r.reason());
    }

    // ---- agent

    @PostMapping("/api/v1/agent/firewall/rules/{id}/status")
    @Operation(summary = "Agent: result of applying, checking or removing a rule")
    public RuleView agentStatus(@AuthenticationPrincipal AgentPrincipal agent, @PathVariable UUID id,
            @Valid @RequestBody AgentRuleStatus s) {
        return rules.agentStatus(agent, id, s);
    }
}
