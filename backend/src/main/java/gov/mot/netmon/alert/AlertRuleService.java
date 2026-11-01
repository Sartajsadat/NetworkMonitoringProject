package gov.mot.netmon.alert;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.alert.AlertDtos.RuleRequest;
import gov.mot.netmon.alert.AlertDtos.RuleTypeInfo;
import gov.mot.netmon.alert.AlertDtos.RuleView;
import gov.mot.netmon.audit.AuditAction;
import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.security.CurrentUser;
import gov.mot.netmon.server.ServerRepository;

@Service
public class AlertRuleService {

    private static final Pattern RESOURCE = Pattern.compile("^[^\\p{Cntrl}]{1,255}$");

    private final AlertRuleRepository rules;
    private final AlertRepository alerts;
    private final ServerRepository servers;
    private final AlertEngine engine;
    private final AuditService audit;
    private final Clock clock;

    public AlertRuleService(AlertRuleRepository rules, AlertRepository alerts, ServerRepository servers, @Lazy AlertEngine engine,
            AuditService audit, Clock clock) {
        this.rules = rules;
        this.alerts = alerts;
        this.servers = servers;
        this.engine = engine;
        this.audit = audit;
        this.clock = clock;
    }

    public List<RuleTypeInfo> types() {
        return Arrays.stream(AlertRuleType.values()).map(t -> new RuleTypeInfo(t, t.category, t.unit.name(), t.needsThreshold(),
                t.minThreshold, t.maxThreshold, t.usesWindow, t.resourceKind, t.serverScoped)).toList();
    }

    @Transactional(readOnly = true)
    public List<RuleView> list() {
        return rules.findAllByOrderByNameAsc().stream().map(this::view).toList();
    }

    RuleView viewOrNull(UUID id) {
        return rules.findById(id).map(this::view).orElse(null);
    }

    private RuleView view(AlertRule r) {
        String server = r.getServerId() == null ? null : servers.findById(r.getServerId()).map(s -> s.getName()).orElse(null);
        long active = alerts.findByRuleIdAndStatusIn(r.getId(), AlertEngine.ACTIVE).size();
        return new RuleView(r.getId(), r.getName(), r.getDescription(), r.getRuleType(), r.getRuleType().unit.name(),
                r.getRuleType().category, r.getSeverity(), r.getThreshold(), r.getWindowSeconds(), r.getCooldownSeconds(),
                r.getServerId(), server, r.getResourceFilter(), r.isEnabled(), r.isBuiltin(), r.getUpdatedAt(), r.getUpdatedBy(),
                active);
    }

    private void validate(RuleRequest req) {
        AlertRuleType t = req.ruleType();
        if (t.needsThreshold()) {
            if (req.threshold() == null || req.threshold().isNaN() || req.threshold() < t.minThreshold || req.threshold() > t.maxThreshold) {
                throw ApiException.badRequest("INVALID_THRESHOLD", "Threshold must be between " + t.minThreshold + " and " + t.maxThreshold);
            }
        }
        if (req.windowSeconds() < 0 || req.windowSeconds() > 86400) throw ApiException.badRequest("INVALID_WINDOW", "Window must be 0 to 86400 seconds");
        if (t.usesWindow && t.needsThreshold() && t != AlertRuleType.FAILED_LOGINS && req.windowSeconds() < 60) {
            throw ApiException.badRequest("INVALID_WINDOW", "Metric rules need a window of at least 60 seconds");
        }
        if (req.cooldownSeconds() < 0 || req.cooldownSeconds() > 604800) throw ApiException.badRequest("INVALID_COOLDOWN", "Cooldown must be 0 to 7 days");
        if (req.serverId() != null) {
            if (!t.serverScoped) throw ApiException.badRequest("INVALID_SCOPE", "This rule type is not about a server");
            if (!servers.existsById(req.serverId())) throw ApiException.badRequest("UNKNOWN_SERVER", "Unknown server");
        }
        if (req.resourceFilter() != null && !req.resourceFilter().isBlank()) {
            if (t.resourceKind == null) throw ApiException.badRequest("INVALID_SCOPE", "This rule type has no resource filter");
            if (!RESOURCE.matcher(req.resourceFilter().trim()).matches()) throw ApiException.badRequest("INVALID_SCOPE", "Invalid resource");
        }
    }

    private void apply(AlertRule r, RuleRequest req, String user, Instant now) {
        String res = req.resourceFilter() == null || req.resourceFilter().isBlank() ? null : req.resourceFilter().trim();
        String desc = req.description() == null || req.description().isBlank() ? null : req.description().trim();
        r.apply(req.name().trim(), desc, req.ruleType(), req.severity(), req.ruleType().needsThreshold() ? req.threshold() : null,
                req.windowSeconds(), req.cooldownSeconds(), req.serverId(), res, req.enabled(), user, now);
    }

    @Transactional
    public RuleView create(RuleRequest req) {
        validate(req);
        String user = CurrentUser.require().username();
        Instant now = clock.instant();
        AlertRule r = new AlertRule(UUID.randomUUID(), user, now);
        apply(r, req, user, now);
        rules.save(r);
        audit.event(AuditAction.ALERT_RULE_CREATED).target("ALERT_RULE", r.getId()).detail("name", r.getName())
                .detail("type", r.getRuleType().name()).detail("threshold", r.getThreshold())
                .detail("windowSeconds", r.getWindowSeconds()).detail("enabled", r.isEnabled()).success();
        return view(r);
    }

    @Transactional
    public RuleView update(UUID id, RuleRequest req) {
        validate(req);
        AlertRule r = rules.findById(id).orElseThrow(() -> ApiException.notFound("Alert rule"));
        String user = CurrentUser.require().username();
        String before = r.getRuleType() + " " + r.getThreshold() + " " + r.getWindowSeconds() + "s " + (r.isEnabled() ? "on" : "off");
        apply(r, req, user, clock.instant());
        engine.closeForRuleChange(r.getId(), user, r.isEnabled() ? "Rule changed by " + user : "Rule disabled by " + user);
        audit.event(AuditAction.ALERT_RULE_UPDATED).target("ALERT_RULE", id).detail("name", r.getName()).detail("before", before)
                .detail("after", r.getRuleType() + " " + r.getThreshold() + " " + r.getWindowSeconds() + "s " + (r.isEnabled() ? "on" : "off"))
                .success();
        return view(r);
    }

    @Transactional
    public void delete(UUID id) {
        AlertRule r = rules.findById(id).orElseThrow(() -> ApiException.notFound("Alert rule"));
        String user = CurrentUser.require().username();
        engine.closeForRuleChange(id, user, "Rule deleted by " + user);
        rules.delete(r);
        audit.event(AuditAction.ALERT_RULE_DELETED).target("ALERT_RULE", id).detail("name", r.getName())
                .detail("type", r.getRuleType().name()).success();
    }
}
