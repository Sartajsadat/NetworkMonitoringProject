package gov.mot.netmon.firewall;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.agent.AgentPrincipal;
import gov.mot.netmon.audit.AuditAction;
import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.capture.AgentTask;
import gov.mot.netmon.capture.AgentTaskRepository;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.common.ClientInfo;
import gov.mot.netmon.event.EventSeverity;
import gov.mot.netmon.firewall.FirewallDtos.AgentRuleStatus;
import gov.mot.netmon.firewall.FirewallDtos.ApprovalView;
import gov.mot.netmon.firewall.FirewallDtos.Preview;
import gov.mot.netmon.firewall.FirewallDtos.RuleDetail;
import gov.mot.netmon.firewall.FirewallDtos.RuleRequest;
import gov.mot.netmon.firewall.FirewallDtos.RuleView;
import gov.mot.netmon.firewall.FirewallDtos.TargetState;
import gov.mot.netmon.security.AuthenticatedUser;
import gov.mot.netmon.security.CurrentUser;
import gov.mot.netmon.server.Server;
import gov.mot.netmon.server.ServerRepository;
import gov.mot.netmon.user.RoleCode;

/**
 * IP allow/block rules: request, preview, four-eyes approval, delivery to the agent, status reports,
 * removal. A rule becomes APPLIED only when the agent has read it back from the firewall - never
 * because a task was sent.
 */
@Service
public class FirewallRuleService {

    static final Set<RoleCode> APPROVERS = Set.of(RoleCode.SUPER_ADMIN, RoleCode.NETWORK_ADMIN);
    /** Statuses in which the rule may be present on the target. */
    static final Set<String> ON_TARGET = Set.of("APPLIED", "UNVERIFIED", "REMOVAL_PENDING");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final FirewallRuleRepository rules;
    private final FirewallIntegrationRepository integrations;
    private final FirewallService firewalls;
    private final AgentTaskRepository tasks;
    private final ServerRepository servers;
    private final FirewallEvents events;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public FirewallRuleService(FirewallRuleRepository rules, FirewallIntegrationRepository integrations, FirewallService firewalls,
            AgentTaskRepository tasks, ServerRepository servers, FirewallEvents events, AuditService audit, JdbcTemplate jdbc,
            Clock clock) {
        this.rules = rules;
        this.integrations = integrations;
        this.firewalls = firewalls;
        this.tasks = tasks;
        this.servers = servers;
        this.events = events;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ scope / preview

    /** Where the rule takes effect, in plain words. A server rule is never a network-wide block. */
    static String scope(FirewallIntegration i, String serverName) {
        if (i.isAgent()) {
            return "Host firewall of server " + (serverName == null ? "?" : serverName)
                    + " only: traffic to and from that one server. This is not a network-wide block.";
        }
        return "Central firewall \"" + i.getName() + "\": dry run only. No adapter is installed for this equipment, so nothing is"
                + " changed on it; an administrator must apply the change on the device.";
    }

    private String serverName(FirewallIntegration i) {
        return i.getServerId() == null ? null : servers.findById(i.getServerId()).map(Server::getName).orElse(null);
    }

    private boolean dryRun(FirewallIntegration i, TargetState t) {
        return !i.isAgent() || !"ENFORCE".equals(i.getMode()) || t == null || !t.enforcementAllowedOnServer();
    }

    private RuleValidator.Check check(FirewallIntegration i, RuleRequest r, TargetState t) {
        RuleValidator.Input in = new RuleValidator.Input(r.action(), r.direction(), r.remote(), r.local(), r.protocol(),
                r.localPort(), r.remotePort());
        return RuleValidator.check(in, r.expiresAt(), clock.instant(), firewalls.protectedCidrs(i), ClientInfo.currentIp(),
                dryRun(i, t));
    }

    private static String backend(TargetState t) {
        return t == null ? "CENTRAL" : t.backend();
    }

    @Transactional(readOnly = true)
    public Preview preview(RuleRequest r) {
        FirewallIntegration i = firewalls.find(r.integrationId());
        TargetState t = firewalls.target(i);
        RuleValidator.Check c = check(i, r, t);
        String cmd = c.spec() == null ? null : RuleValidator.preview(c.spec(), "<tag>", backend(t));
        String sn = serverName(i);
        return new Preview(c.ok(), c.errorIssues(), c.warningIssues(), scope(i, sn), i.isAgent() ? "SERVER" : "CENTRAL",
                i.isAgent() ? sn : i.getName(), cmd, dryRun(i, t) ? "DRY_RUN" : "ENFORCE", t == null ? null : t.backend());
    }

    // ------------------------------------------------------------------ request / decide

    @Transactional
    public RuleView request(RuleRequest r) {
        AuthenticatedUser user = CurrentUser.require();
        FirewallIntegration i = firewalls.find(r.integrationId());
        TargetState t = firewalls.target(i);
        RuleValidator.Check c = check(i, r, t);
        if (!c.ok()) throw ApiException.badRequest("RULE_INVALID", String.join("; ", c.errors()));
        if (!c.warnings().isEmpty() && !r.acknowledgeWarnings()) {
            throw ApiException.conflict("WARNINGS_NOT_ACKNOWLEDGED", "Read and acknowledge the warnings: " + String.join("; ", c.warnings()));
        }
        String tag = newTag();
        Instant now = clock.instant();
        FirewallRule rule = new FirewallRule(i.getId(), tag, c.spec(), r.reason().trim(), FirewallService.blank(r.reference()),
                r.expiresAt(), RuleValidator.preview(c.spec(), tag, backend(t)), String.join("\n", c.warnings()), user.username(), now);
        rules.save(rule);
        audit.event(AuditAction.FIREWALL_RULE_REQUESTED).target("FIREWALL_RULE", rule.getId()).detail("target", i.getName())
                .detail("rule", FirewallEvents.describe(rule)).detail("expiresAt", r.expiresAt())
                .detail("warnings", c.warnings().isEmpty() ? null : c.warnings()).reason(rule.getReason()).success();
        events.rule(rule, i, "FIREWALL_RULE_REQUESTED", EventSeverity.INFO,
                "Firewall rule requested by " + user.username() + " on " + i.getName() + ": " + FirewallEvents.describe(rule));
        return view(rule, i);
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

    private FirewallRule find(UUID id) {
        return rules.findById(id).orElseThrow(() -> ApiException.notFound("Firewall rule"));
    }

    /**
     * Approval re-runs every safety check against the current state (the protected list or the
     * target's mode may have changed since the request), then sends the rule to the target.
     */
    @Transactional
    public RuleView approve(UUID id, String comment) {
        AuthenticatedUser user = CurrentUser.require();
        FirewallRule rule = find(id);
        FirewallIntegration i = firewalls.find(rule.getIntegrationId());
        requireDecider(user, rule);
        TargetState t = firewalls.target(i);
        RuleValidator.Check c = check(i, requestOf(rule), t);
        if (!c.ok()) throw ApiException.conflict("RULE_NOW_UNSAFE", "The rule no longer passes the safety checks: " + String.join("; ", c.errors()));
        Instant now = clock.instant();
        rule.decide(user.username(), now);
        boolean dry = dryRun(i, t);
        rule.approved(dry ? "DRY_RUN" : "ENFORCE", now);
        recordDecision(rule, "APPROVED", user.username(), now, comment);
        audit.event(AuditAction.FIREWALL_RULE_APPROVED).target("FIREWALL_RULE", id).detail("target", i.getName())
                .detail("rule", FirewallEvents.describe(rule)).detail("requestedBy", rule.getRequestedBy())
                .detail("mode", rule.getModeAtApply()).reason(comment).success();
        events.rule(rule, i, "FIREWALL_RULE_APPROVED", EventSeverity.INFO, "Firewall rule approved by " + user.username()
                + " (" + (dry ? "dry run" : "enforce") + "): " + FirewallEvents.describe(rule));
        if (i.isAgent()) {
            tasks.save(AgentTask.firewall(i.getServerId(), rule.getId(), "APPLY_FIREWALL_RULE", payload(rule, dry), now));
        } else {
            // Central equipment without an adapter: the preview is the result. Nothing was sent anywhere.
            setStatus(rule, i, "DRY_RUN", "Dry run only: no adapter for this equipment. Apply the previewed change on the device"
                    + " through its own change process.", "platform");
        }
        return view(rule, i);
    }

    @Transactional
    public RuleView reject(UUID id, String comment) {
        AuthenticatedUser user = CurrentUser.require();
        FirewallRule rule = find(id);
        FirewallIntegration i = firewalls.find(rule.getIntegrationId());
        requireDecider(user, rule);
        if (comment == null || comment.isBlank()) throw ApiException.badRequest("COMMENT_REQUIRED", "Say why the request is rejected");
        Instant now = clock.instant();
        rule.decide(user.username(), now);
        rule.status("REJECTED", comment, now);
        recordDecision(rule, "REJECTED", user.username(), now, comment);
        audit.event(AuditAction.FIREWALL_RULE_REJECTED).target("FIREWALL_RULE", id).detail("target", i.getName())
                .detail("rule", FirewallEvents.describe(rule)).reason(comment).success();
        events.rule(rule, i, "FIREWALL_RULE_REJECTED", EventSeverity.INFO, "Firewall rule rejected by " + user.username() + ": "
                + FirewallEvents.describe(rule));
        return view(rule, i);
    }

    private void requireDecider(AuthenticatedUser user, FirewallRule rule) {
        if (!"REQUESTED".equals(rule.getStatus())) throw ApiException.conflict("NOT_PENDING", "This request is no longer waiting for a decision");
        if (user.roles().stream().noneMatch(APPROVERS::contains)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "APPROVER_REQUIRED", "Only a super or network administrator can decide");
        }
        if (user.username().equals(rule.getRequestedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_APPROVAL", "A request must be decided by someone other than the requester");
        }
    }

    private void recordDecision(FirewallRule rule, String decision, String by, Instant now, String comment) {
        jdbc.update("INSERT INTO access_policy_approvals (rule_id, decision, decided_by, decided_at, comment) VALUES (?, ?, ?, ?, ?)",
                rule.getId(), decision, by, java.sql.Timestamp.from(now), FirewallService.blank(comment));
    }

    static RuleRequest requestOf(FirewallRule r) {
        return new RuleRequest(r.getIntegrationId(), r.getAction(), r.getDirection(), r.getRemoteCidr(), r.getLocalCidr(),
                r.getProtocol(), r.getLocalPort(), r.getRemotePort(), r.getReason(), r.getReference(), null, true);
    }

    static Map<String, Object> payload(FirewallRule r, boolean dryRun) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ruleId", r.getId().toString());
        p.put("tag", r.getTag());
        p.put("action", r.getAction());
        p.put("direction", r.getDirection());
        p.put("remote", r.getRemoteCidr());
        if (r.getLocalCidr() != null) p.put("local", r.getLocalCidr());
        p.put("protocol", r.getProtocol());
        if (r.getLocalPort() != null) p.put("localPort", r.getLocalPort());
        if (r.getRemotePort() != null) p.put("remotePort", r.getRemotePort());
        p.put("mode", dryRun ? "DRY_RUN" : "ENFORCE");
        if (r.getExpiresAt() != null) p.put("expiresAt", r.getExpiresAt().toString());
        return p;
    }

    // ------------------------------------------------------------------ cancel / remove

    @Transactional
    public RuleView cancel(UUID id) {
        AuthenticatedUser user = CurrentUser.require();
        FirewallRule rule = find(id);
        FirewallIntegration i = firewalls.find(rule.getIntegrationId());
        if (!"REQUESTED".equals(rule.getStatus())) throw ApiException.conflict("NOT_PENDING", "Only a waiting request can be cancelled");
        if (!user.username().equals(rule.getRequestedBy()) && user.roles().stream().noneMatch(APPROVERS::contains)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the requester or an administrator can cancel");
        }
        rule.status("CANCELLED", "Cancelled by " + user.username(), clock.instant());
        audit.event(AuditAction.FIREWALL_RULE_CANCELLED).target("FIREWALL_RULE", id).detail("rule", FirewallEvents.describe(rule)).success();
        events.rule(rule, i, "FIREWALL_RULE_CANCELLED", EventSeverity.INFO, "Firewall rule request cancelled by " + user.username());
        return view(rule, i);
    }

    /** Removal of an approved rule. On a server this is a REMOVE task; the rule is REMOVED when the agent confirms. */
    @Transactional
    public RuleView remove(UUID id, String reason) {
        AuthenticatedUser user = CurrentUser.require();
        FirewallRule rule = find(id);
        FirewallIntegration i = firewalls.find(rule.getIntegrationId());
        String st = rule.getStatus();
        if (!Set.of("APPLIED", "DRY_RUN", "UNVERIFIED", "FAILED", "APPROVED").contains(st)) {
            throw ApiException.conflict("NOT_REMOVABLE", "This rule is not active");
        }
        Instant now = clock.instant();
        rule.requestRemoval(user.username(), reason, false, now);
        audit.event(AuditAction.FIREWALL_RULE_REMOVAL_REQUESTED).target("FIREWALL_RULE", id).detail("target", i.getName())
                .detail("rule", FirewallEvents.describe(rule)).reason(reason).success();
        startRemoval(rule, i, user.username(), now);
        return view(rule, i);
    }

    /** Shared by manual removal and expiry. */
    void startRemoval(FirewallRule rule, FirewallIntegration i, String by, Instant now) {
        tasks.findByFirewallRuleIdAndStatus(rule.getId(), "PENDING").forEach(AgentTask::cancel);
        boolean maybeOnTarget = i.isAgent() && (ON_TARGET.contains(rule.getStatus()) || "FAILED".equals(rule.getStatus())
                || ("APPROVED".equals(rule.getStatus()) && "ENFORCE".equals(rule.getModeAtApply())));
        String done = rule.isExpiring() ? "EXPIRED" : "REMOVED";
        if (maybeOnTarget) {
            // Ask the agent to delete it (a no-op if it is not there) and wait for its confirmation.
            rule.status("REMOVAL_PENDING", rule.isExpiring() ? "Expired: waiting for the agent to remove it" : "Waiting for the agent to remove it", now);
            tasks.save(AgentTask.firewall(i.getServerId(), rule.getId(), "REMOVE_FIREWALL_RULE",
                    Map.of("ruleId", rule.getId().toString(), "tag", rule.getTag(), "mode", "ENFORCE".equals(rule.getModeAtApply())
                            ? "ENFORCE" : "DRY_RUN"), now));
            events.rule(rule, i, "FIREWALL_RULE_REMOVAL", EventSeverity.INFO, (rule.isExpiring() ? "Firewall rule expired" : "Removal requested by " + by)
                    + "; the agent will remove it: " + FirewallEvents.describe(rule));
        } else {
            setStatus(rule, i, done, rule.isExpiring() ? "Expired (dry run: nothing was on the target)" : "Removed (dry run: nothing was on the target)", by);
        }
    }

    // ------------------------------------------------------------------ status

    /** Every status change goes to the audit log and the timeline. */
    void setStatus(FirewallRule rule, FirewallIntegration i, String status, String message, String actor) {
        String before = rule.getStatus();
        rule.status(status, message, clock.instant());
        if (before.equals(status)) return;
        var a = audit.event(AuditAction.FIREWALL_RULE_STATUS_CHANGED).actor(null, actor).target("FIREWALL_RULE", rule.getId())
                .detail("target", i.getName()).detail("from", before).detail("to", status).detail("message", message);
        if ("FAILED".equals(status) || "UNVERIFIED".equals(status)) a.failure(); else a.success();
        EventSeverity sev = switch (status) {
            case "FAILED", "UNVERIFIED" -> EventSeverity.ERROR;
            default -> EventSeverity.INFO;
        };
        String text = switch (status) {
            case "APPLIED" -> "Firewall rule applied and verified on " + i.getName();
            case "DRY_RUN" -> "Firewall rule checked in dry run on " + i.getName() + " (nothing changed)";
            case "FAILED" -> "Firewall rule could not be applied on " + i.getName();
            case "UNVERIFIED" -> "Firewall rule could not be verified on " + i.getName();
            case "REMOVED" -> "Firewall rule removed from " + i.getName();
            case "EXPIRED" -> "Firewall rule expired and was removed from " + i.getName();
            default -> "Firewall rule is now " + status + " on " + i.getName();
        };
        events.rule(rule, i, "FIREWALL_RULE_" + status, sev, text + ": " + FirewallEvents.describe(rule)
                + (message == null ? "" : " - " + message));
    }

    /**
     * Result reported by the agent. States: APPLIED (read back from the firewall), DRY_RUN (checked
     * only), FAILED, UNVERIFIED (command succeeded but the rule was not found afterwards), REMOVED,
     * NOT_PRESENT (removal found nothing to delete).
     */
    @Transactional
    public RuleView agentStatus(AgentPrincipal agent, UUID ruleId, AgentRuleStatus s) {
        FirewallRule rule = find(ruleId);
        FirewallIntegration i = firewalls.find(rule.getIntegrationId());
        if (!agent.serverId().equals(i.getServerId())) throw ApiException.notFound("Firewall rule");
        String actor = "agent@" + serverName(i);
        rule.agentCommand(s.command());
        String state = s.state();
        String cur = rule.getStatus();
        switch (state) {
            case "FAILED" -> {
                if ("REMOVAL_PENDING".equals(cur)) {
                    setStatus(rule, i, "UNVERIFIED", "Removal failed: " + (s.message() == null ? "no details" : s.message())
                            + ". The rule may still be present on the server.", actor);
                } else if (!Set.of("APPROVED", "APPLIED", "UNVERIFIED", "FAILED").contains(cur)) {
                    throw ApiException.conflict("STALE_STATUS", "The rule is " + cur + "; this result is no longer relevant");
                } else {
                    setStatus(rule, i, "FAILED", s.message(), actor);
                }
            }
            case "APPLIED", "DRY_RUN", "UNVERIFIED" -> {
                if (!Set.of("APPROVED", "APPLIED", "UNVERIFIED", "FAILED").contains(cur)) {
                    throw ApiException.conflict("STALE_STATUS", "The rule is " + cur + "; this result is no longer relevant");
                }
                if ("DRY_RUN".equals(state) && "ENFORCE".equals(rule.getModeAtApply())) {
                    // The server refused enforcement locally: record that clearly instead of pretending it applied.
                    setStatus(rule, i, "FAILED", "The server only ran a dry run: " + (s.message() == null ? "enforcement is not allowed there" : s.message()), actor);
                } else {
                    setStatus(rule, i, state, s.message(), actor);
                }
            }
            case "REMOVED", "NOT_PRESENT" -> {
                if (!"REMOVAL_PENDING".equals(cur)) throw ApiException.conflict("STALE_STATUS", "No removal is pending for this rule");
                setStatus(rule, i, rule.isExpiring() ? "EXPIRED" : "REMOVED",
                        s.message() != null ? s.message() : "NOT_PRESENT".equals(state) ? "The rule was not present on the server" : null, actor);
            }
            default -> throw ApiException.badRequest("INVALID_STATE", "Unknown firewall rule state");
        }
        return view(rule, i);
    }

    // ------------------------------------------------------------------ queries

    RuleView view(FirewallRule r, FirewallIntegration i) {
        String sn = serverName(i);
        List<String> warnings = r.getWarnings() == null || r.getWarnings().isBlank() ? List.of() : List.of(r.getWarnings().split("\n"));
        return new RuleView(r.getId(), i.getId(), i.getName(), i.getKind(), i.getServerId(), sn, scope(i, sn), r.getTag(), r.getAction(),
                r.getDirection(), r.getRemoteCidr(), r.getLocalCidr(), r.getProtocol(), r.getLocalPort(), r.getRemotePort(), r.getReason(),
                r.getReference(), r.getExpiresAt(), r.getStatus(), r.getStatusMessage(), r.getModeAtApply(), r.getPreview(), warnings,
                r.getAgentCommand(), r.getRequestedBy(), r.getRequestedAt(), r.getDecidedBy(), r.getDecidedAt(), r.getAppliedAt(),
                r.getLastVerifiedAt(), r.getRemovalRequestedBy(), r.getRemovalRequestedAt(), r.getRemovalReason(), r.getRemovedAt(),
                r.isExpiring());
    }

    @Transactional(readOnly = true)
    public Page<RuleView> list(UUID integrationId, List<String> statuses, String address, int page, int size) {
        Specification<FirewallRule> spec = (root, q, cb) -> cb.conjunction();
        if (integrationId != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("integrationId"), integrationId));
        if (statuses != null && !statuses.isEmpty()) spec = spec.and((root, q, cb) -> root.get("status").in(statuses));
        if (address != null && !address.isBlank()) {
            String like = "%" + address.trim().toLowerCase(java.util.Locale.ROOT) + "%";
            spec = spec.and((root, q, cb) -> cb.like(cb.lower(root.get("remoteCidr")), like));
        }
        Map<UUID, FirewallIntegration> byId = new java.util.HashMap<>();
        integrations.findAll().forEach(x -> byId.put(x.getId(), x));
        return rules.findAll(spec, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200), Sort.by(Sort.Direction.DESC, "requestedAt")))
                .map(r -> view(r, byId.get(r.getIntegrationId())));
    }

    @Transactional(readOnly = true)
    public RuleDetail get(UUID id) {
        FirewallRule r = find(id);
        FirewallIntegration i = firewalls.find(r.getIntegrationId());
        List<ApprovalView> approvals = new ArrayList<>(jdbc.query(
                "SELECT decision, decided_by, decided_at, comment FROM access_policy_approvals WHERE rule_id = ? ORDER BY decided_at",
                (rs, n) -> new ApprovalView(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getString(4)), id));
        return new RuleDetail(view(r, i), approvals);
    }
}
