package gov.mot.netmon.firewall;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import gov.mot.netmon.audit.AuditAction;
import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.event.EventSeverity;
import gov.mot.netmon.firewall.AppDtos.AppRequest;
import gov.mot.netmon.firewall.AppDtos.AppView;
import gov.mot.netmon.firewall.AppDtos.PolicyPreview;
import gov.mot.netmon.firewall.AppDtos.PolicyRequest;
import gov.mot.netmon.firewall.AppDtos.Support;
import gov.mot.netmon.firewall.FirewallDtos.RuleView;
import gov.mot.netmon.firewall.FirewallDtos.TargetState;
import gov.mot.netmon.metrics.SnapshotStore;
import gov.mot.netmon.security.CurrentUser;
import gov.mot.netmon.server.Server;
import gov.mot.netmon.server.ServerRepository;

/**
 * Application inventory and application access policy requests (Phase 8). Once requested, a
 * policy is a firewall rule and follows the IP rule workflow: second-person approval, delivery to
 * the agent, read-back verification, drift detection, expiry and removal.
 */
@Service
public class ApplicationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApplicationRepository apps;
    private final ServerRepository servers;
    private final FirewallIntegrationRepository integrations;
    private final FirewallRuleRepository rules;
    private final FirewallService firewalls;
    private final FirewallRuleService ruleService;
    private final FirewallEvents events;
    private final SnapshotStore snapshots;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    public ApplicationService(ApplicationRepository apps, ServerRepository servers, FirewallIntegrationRepository integrations,
            FirewallRuleRepository rules, FirewallService firewalls, FirewallRuleService ruleService, FirewallEvents events,
            SnapshotStore snapshots, AuditService audit, ObjectMapper json, Clock clock) {
        this.apps = apps;
        this.servers = servers;
        this.integrations = integrations;
        this.rules = rules;
        this.firewalls = firewalls;
        this.ruleService = ruleService;
        this.events = events;
        this.snapshots = snapshots;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ inventory

    Support support(Application a) {
        var i = integrations.findByServerId(a.getServerId()).orElse(null);
        if (i == null) return new Support(false, "This server has no firewall target yet (add it under Firewall integrations)", null, null, false);
        TargetState t = firewalls.target(i);
        String why = FirewallRuleService.appSupport(i, t, a.getIdentityKind());
        boolean enforcing = "ENFORCE".equals(i.getMode()) && t != null && t.enforcementAllowedOnServer();
        return new Support(why == null, why, i.getId(), i.getMode(), why == null && enforcing);
    }

    /** Processes seen running as this user / program in the latest report: evidence the identity is right. */
    List<String> seen(Application a) {
        return snapshots.find(a.getServerId()).map(s -> s.report().processes() == null ? List.<String>of() : s.report().processes().stream()
                .filter(p -> switch (a.getIdentityKind()) {
                    case "LINUX_USER" -> a.getIdentityValue().equals(p.user());
                    case "WINDOWS_PROGRAM" -> p.name() != null && a.getIdentityValue().toLowerCase().endsWith("\\" + p.name().toLowerCase());
                    default -> false;
                })
                .map(p -> p.name() + " (" + p.pid() + ")").distinct().limit(10).toList()).orElse(List.of());
    }

    AppView view(Application a, Map<UUID, String> names) {
        Server s = servers.findById(a.getServerId()).orElse(null);
        long active = rules.countByApplicationIdAndStatusIn(a.getId(), FirewallService.RULE_ACTIVE);
        return new AppView(a.getId(), a.getServerId(), s == null ? null : s.getName(), s == null ? null : s.getOsFamily().name(),
                a.getName(), a.getDescription(), a.getOwner(), a.getIdentityKind(), a.getIdentityValue(), support(a), active, seen(a),
                a.getCreatedBy(), a.getCreatedAt(), a.getUpdatedBy(), a.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public List<AppView> list(UUID serverId) {
        List<Application> all = serverId == null ? apps.findAllByOrderByNameAsc() : apps.findByServerIdOrderByNameAsc(serverId);
        return all.stream().map(a -> view(a, null)).toList();
    }

    @Transactional(readOnly = true)
    public AppView get(UUID id) {
        return view(find(id), null);
    }

    Application find(UUID id) {
        return apps.findById(id).orElseThrow(() -> ApiException.notFound("Application"));
    }

    private void validate(AppRequest r, Server s, UUID existingId) {
        String problem = AppPolicyValidator.identityProblem(r.identityKind(), r.identityValue().trim(), s.getOsFamily().name());
        if (problem != null) throw ApiException.badRequest("INVALID_IDENTITY", problem);
    }

    @Transactional
    public AppView create(AppRequest r) {
        Server s = servers.findById(r.serverId()).orElseThrow(() -> ApiException.badRequest("UNKNOWN_SERVER", "Unknown server"));
        validate(r, s, null);
        String name = r.name().trim();
        String value = r.identityValue().trim();
        if (apps.existsByServerIdAndName(s.getId(), name)) throw ApiException.conflict("APPLICATION_EXISTS", "This server already has an application with this name");
        if (apps.existsByServerIdAndIdentityKindAndIdentityValue(s.getId(), r.identityKind(), value)) {
            throw ApiException.conflict("IDENTITY_EXISTS", "Another application on this server already uses this identity");
        }
        Application a = new Application(s.getId(), name, FirewallService.blank(r.description()), FirewallService.blank(r.owner()),
                r.identityKind(), value, CurrentUser.require().username(), clock.instant());
        apps.save(a);
        audit.event(AuditAction.APPLICATION_REGISTERED).target("APPLICATION", a.getId()).detail("server", s.getName())
                .detail("application", a.label()).success();
        return view(a, null);
    }

    @Transactional
    public AppView update(UUID id, AppRequest r) {
        Application a = find(id);
        if (!a.getServerId().equals(r.serverId())) throw ApiException.badRequest("SERVER_FIXED", "An application cannot move to another server");
        Server s = servers.findById(a.getServerId()).orElseThrow(() -> ApiException.notFound("Server"));
        validate(r, s, id);
        boolean identityChanged = !a.getIdentityKind().equals(r.identityKind()) || !a.getIdentityValue().equals(r.identityValue().trim());
        if (identityChanged && rules.countByApplicationIdAndStatusIn(id, FirewallService.RULE_ACTIVE) > 0) {
            throw ApiException.conflict("POLICIES_ACTIVE", "Remove this application's policies before changing its identity");
        }
        String before = a.label();
        a.update(r.name().trim(), FirewallService.blank(r.description()), FirewallService.blank(r.owner()), r.identityKind(),
                r.identityValue().trim(), CurrentUser.require().username(), clock.instant());
        audit.event(AuditAction.APPLICATION_UPDATED).target("APPLICATION", id).detail("from", before).detail("to", a.label()).success();
        return view(a, null);
    }

    @Transactional
    public void delete(UUID id) {
        Application a = find(id);
        if (rules.countByApplicationIdAndStatusIn(id, FirewallService.RULE_ACTIVE) > 0) {
            throw ApiException.conflict("POLICIES_ACTIVE", "Remove this application's policies first");
        }
        if (rules.countByApplicationId(id) > 0) {
            throw ApiException.conflict("POLICY_HISTORY", "This application has policy history; it is kept for the audit trail");
        }
        apps.delete(a);
        audit.event(AuditAction.APPLICATION_DELETED).target("APPLICATION", id).detail("application", a.label()).success();
    }

    // ------------------------------------------------------------------ policies

    private record Prepared(Application app, FirewallIntegration target, TargetState state, AppPolicyValidator.Check check,
            String unsupported, boolean dryRun) {
    }

    private Prepared prepare(PolicyRequest r) {
        Application a = find(r.applicationId());
        FirewallIntegration i = integrations.findByServerId(a.getServerId()).orElseThrow(() -> ApiException.conflict("NO_TARGET",
                "This server has no firewall target yet. Add it under Firewall integrations first."));
        TargetState t = firewalls.target(i);
        String unsupported = FirewallRuleService.appSupport(i, t, a.getIdentityKind());
        boolean dry = !"ENFORCE".equals(i.getMode()) || t == null || !t.enforcementAllowedOnServer();
        AppPolicyValidator.Check c = AppPolicyValidator.check(r.appMode(), a.getIdentityKind(), a.getIdentityValue(), r.destinations(),
                r.expiresAt(), clock.instant(), dry);
        return new Prepared(a, i, t, c, unsupported, dry);
    }

    static String scope(Application a, String server) {
        return "Only connections started by " + a.label() + " on server " + server
                + ". Other applications on that server, other servers and the network are not affected.";
    }

    @Transactional(readOnly = true)
    public PolicyPreview preview(PolicyRequest r) {
        Prepared p = prepare(r);
        Application a = p.app();
        String server = servers.findById(a.getServerId()).map(Server::getName).orElse("?");
        String cmd = p.check().ok() && r.appMode() != null && AppPolicyValidator.MODES.contains(r.appMode())
                ? AppPolicyValidator.preview(r.appMode(), a.getIdentityKind(), a.getIdentityValue(), p.check().destinations(), "<tag>",
                        p.state() == null ? null : p.state().backend())
                : null;
        return new PolicyPreview(p.check().ok() && p.unsupported() == null, p.check().errorIssues(), p.check().warningIssues(),
                scope(a, server), server, cmd, p.dryRun() ? "DRY_RUN" : "ENFORCE", p.state() == null ? null : p.state().backend(),
                p.unsupported());
    }

    @Transactional
    public RuleView request(PolicyRequest r) {
        String user = CurrentUser.require().username();
        Prepared p = prepare(r);
        if (p.unsupported() != null) throw ApiException.conflict("APP_CONTROL_UNSUPPORTED", p.unsupported());
        if (!p.check().ok()) {
            throw ApiException.badRequest("RULE_INVALID", String.join("; ", p.check().errorIssues().stream().map(RuleValidator.Issue::text).toList()));
        }
        if (!p.check().warningIssues().isEmpty() && !r.acknowledgeWarnings()) {
            throw ApiException.conflict("WARNINGS_NOT_ACKNOWLEDGED", "Read and acknowledge the warnings");
        }
        String tag = newTag();
        Application a = p.app();
        String destinationsJson;
        try {
            destinationsJson = json.writeValueAsString(p.check().destinations());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        FirewallRule rule = FirewallRule.application(p.target().getId(), tag, a.getId(), a.label(), r.appMode(), destinationsJson,
                r.reason().trim(), FirewallService.blank(r.reference()), r.expiresAt(),
                AppPolicyValidator.preview(r.appMode(), a.getIdentityKind(), a.getIdentityValue(), p.check().destinations(), tag,
                        p.state() == null ? null : p.state().backend()),
                RuleValidator.encode(p.check().warningIssues()), user, clock.instant());
        rules.save(rule);
        audit.event(AuditAction.APP_POLICY_REQUESTED).target("FIREWALL_RULE", rule.getId()).detail("application", a.label())
                .detail("mode", r.appMode()).detail("destinations", p.check().destinations().size()).detail("expiresAt", r.expiresAt())
                .reason(rule.getReason()).success();
        events.rule(rule, p.target(), "FIREWALL_RULE_REQUESTED", EventSeverity.INFO,
                "Application access policy requested by " + user + ": " + FirewallEvents.describe(rule));
        return ruleService.view(rule, p.target());
    }

    private String newTag() {
        for (int n = 0; n < 5; n++) {
            byte[] b = new byte[4];
            RANDOM.nextBytes(b);
            String tag = HexFormat.of().formatHex(b);
            if (rules.findByTag(tag).isEmpty()) return tag;
        }
        throw new IllegalStateException("Could not allocate a rule tag");
    }

    static Map<UUID, String> none() {
        return Map.of();
    }

    static <T> Map<UUID, T> index(List<T> l, java.util.function.Function<T, UUID> id) {
        return l.stream().collect(Collectors.toMap(id, x -> x));
    }

    Instant now() {
        return clock.instant();
    }
}
