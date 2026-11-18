package gov.mot.netmon.firewall;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
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
import gov.mot.netmon.capture.AgentTask;
import gov.mot.netmon.capture.AgentTaskRepository;
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
    private final AgentTaskRepository tasks;

    public ApplicationService(ApplicationRepository apps, ServerRepository servers, FirewallIntegrationRepository integrations,
            FirewallRuleRepository rules, FirewallService firewalls, FirewallRuleService ruleService, FirewallEvents events,
            SnapshotStore snapshots, AuditService audit, ObjectMapper json, Clock clock, AgentTaskRepository tasks) {
        this.tasks = tasks;
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
        // No target yet: judge from the agent's report as a not-yet-saved target (the switch creates it, in test mode).
        if (i == null) i = new FirewallIntegration("Host firewall", "SERVER_AGENT", a.getServerId(), null, null, clock.instant());
        TargetState t = firewalls.target(i);
        String why = FirewallRuleService.appSupport(i, t, a.getIdentityKind());
        boolean enforcing = "ENFORCE".equals(i.getMode()) && t != null && t.enforcementAllowedOnServer();
        return new Support(why == null, why, i.getCreatedBy() == null ? null : i.getId(), i.getMode(), why == null && enforcing);
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
                a.getName(), a.getDescription(), a.getOwner(), a.getIdentityKind(), a.getIdentityValue(), support(a), active, internet(a), seen(a),
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

    // ------------------------------------------------------------------ internet switch

    /** Rules that still matter for the switch: active ones, plus a failed one until it is retried or cleared. */
    static final Set<String> SWITCH_RELEVANT;
    static {
        Set<String> s = new HashSet<>(FirewallService.RULE_ACTIVE);
        s.add("FAILED");
        SWITCH_RELEVANT = Set.copyOf(s);
    }

    private static boolean isSwitchBlock(FirewallRule r) {
        return "BLOCK_INTERNET".equals(r.getAppMode()) && (r.getDestinations() == null || "[]".equals(r.getDestinations()));
    }

    /** What the switch shows, derived only from rule statuses reported by the agent. */
    AppDtos.Internet internet(Application a) {
        List<FirewallRule> current = rules.findByApplicationIdAndStatusIn(a.getId(), SWITCH_RELEVANT);
        List<FirewallRule> live = current.stream().filter(r -> !"REMOVAL_PENDING".equals(r.getStatus())).toList();
        if (live.isEmpty()) {
            boolean removing = current.stream().anyMatch(r -> "REMOVAL_PENDING".equals(r.getStatus()));
            boolean stuck = current.stream().anyMatch(r -> "UNVERIFIED".equals(r.getStatus()));
            return new AppDtos.Internet(stuck ? "FAILED" : removing ? "TURNING_ON" : "ON", null, null);
        }
        FirewallRule r = live.stream().max(java.util.Comparator.comparing(FirewallRule::getRequestedAt)).orElseThrow();
        if (!isSwitchBlock(r)) {
            return new AppDtos.Internet("REQUESTED".equals(r.getStatus()) ? "WAITING_APPROVAL" : "CUSTOM", r.getStatusMessage(), r.getId());
        }
        String state = switch (r.getStatus()) {
            case "APPLIED" -> "OFF";
            case "DRY_RUN" -> "OFF_TEST";
            case "APPROVED" -> "TURNING_OFF";
            case "REQUESTED" -> "WAITING_APPROVAL";
            default -> "FAILED"; // FAILED, UNVERIFIED
        };
        return new AppDtos.Internet(state, r.getStatusMessage(), r.getId());
    }

    /**
     * The simple on/off switch. Off: a "block internet" policy (private and internal networks stay
     * reachable) is sent to the server's agent straight away, without a separate request and approval;
     * it shows as Off only once the agent has read it back from the firewall. On: every policy of the
     * application is removed. Each switch is audited and on the timeline. Whether the server really
     * blocks or only checks (test mode) is still decided by the server's firewall mode.
     */
    @Transactional
    public AppView setInternet(UUID id, boolean allowed) {
        String user = CurrentUser.require().username();
        Application a = find(id);
        Server server = servers.findById(a.getServerId()).orElseThrow(() -> ApiException.notFound("Server"));
        Instant now = clock.instant();
        List<FirewallRule> current = rules.findByApplicationIdAndStatusIn(id, SWITCH_RELEVANT);
        if (!allowed && current.stream().anyMatch(r -> isSwitchBlock(r) && Set.of("APPLIED", "DRY_RUN", "APPROVED").contains(r.getStatus()))
                && current.stream().noneMatch(r -> !isSwitchBlock(r) && !"REMOVAL_PENDING".equals(r.getStatus()))) {
            return view(a, null); // already off (or turning off)
        }
        FirewallIntegration target = null;
        TargetState state = null;
        boolean dry = true;
        if (!allowed) {
            // Check first, so a refused switch changes nothing.
            target = firewalls.serverTarget(server, user);
            state = firewalls.target(target);
            String unsupported = FirewallRuleService.appSupport(target, state, a.getIdentityKind());
            if (unsupported != null) throw ApiException.conflict("APP_CONTROL_UNSUPPORTED", unsupported);
            dry = !"ENFORCE".equals(target.getMode()) || state == null || !state.enforcementAllowedOnServer();
            AppPolicyValidator.Check c = AppPolicyValidator.check("BLOCK_INTERNET", a.getIdentityKind(), a.getIdentityValue(), List.of(),
                    null, now, dry);
            if (!c.ok()) {
                throw ApiException.badRequest("RULE_INVALID", String.join("; ", c.errorIssues().stream().map(RuleValidator.Issue::text).toList()));
            }
        }
        // Clear whatever is there (older policies, a failed attempt, a request still waiting).
        String why = allowed ? "Internet turned on with the switch by " + user : "Replaced by the internet switch (" + user + ")";
        for (FirewallRule r : current) {
            if ("REMOVAL_PENDING".equals(r.getStatus())) continue;
            FirewallIntegration ri = firewalls.find(r.getIntegrationId());
            if ("REQUESTED".equals(r.getStatus())) {
                r.status("CANCELLED", why, now);
                audit.event(AuditAction.FIREWALL_RULE_CANCELLED).target("FIREWALL_RULE", r.getId()).detail("rule", FirewallEvents.describe(r))
                        .reason(why).success();
                continue;
            }
            r.requestRemoval(user, why, false, now);
            audit.event(AuditAction.FIREWALL_RULE_REMOVAL_REQUESTED).target("FIREWALL_RULE", r.getId()).detail("target", ri.getName())
                    .detail("rule", FirewallEvents.describe(r)).reason(why).success();
            ruleService.startRemoval(r, ri, user, now);
        }
        if (!allowed) {
            String tag = newTag();
            String backend = state == null ? null : state.backend();
            AppPolicyValidator.Check c = AppPolicyValidator.check("BLOCK_INTERNET", a.getIdentityKind(), a.getIdentityValue(), List.of(),
                    null, now, dry);
            FirewallRule rule = FirewallRule.application(target.getId(), tag, a.getId(), a.label(), "BLOCK_INTERNET", "[]",
                    "Internet turned off with the switch by " + user, null, null,
                    AppPolicyValidator.preview("BLOCK_INTERNET", a.getIdentityKind(), a.getIdentityValue(), List.of(), tag, backend),
                    RuleValidator.encode(c.warningIssues()), user, now);
            rule.decide(user, now);
            rule.approved(dry ? "DRY_RUN" : "ENFORCE", now);
            // Flush now: the approval record below is written with plain SQL and references this rule.
            rules.saveAndFlush(rule);
            ruleService.recordDecision(rule, "APPROVED", user, now, "Internet switch: applied directly");
            events.rule(rule, target, "FIREWALL_RULE_APPROVED", EventSeverity.INFO, "Internet turned off for " + a.label() + " by " + user
                    + (dry ? " (test mode: checked only)" : "") + ": " + FirewallEvents.describe(rule));
            tasks.save(AgentTask.firewall(server.getId(), rule.getId(), "APPLY_FIREWALL_RULE", ruleService.payload(rule, dry, a), now));
        }
        audit.event(AuditAction.APP_INTERNET_SWITCHED).target("APPLICATION", id).detail("application", a.label())
                .detail("server", server.getName()).detail("internet", allowed ? "ON" : "OFF")
                .detail("mode", allowed ? null : dry ? "DRY_RUN" : "ENFORCE").success();
        return view(a, null);
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
