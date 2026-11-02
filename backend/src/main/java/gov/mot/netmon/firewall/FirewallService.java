package gov.mot.netmon.firewall;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.agent.AgentProtocol;
import gov.mot.netmon.audit.AuditAction;
import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.firewall.FirewallDtos.IntegrationRequest;
import gov.mot.netmon.firewall.FirewallDtos.IntegrationView;
import gov.mot.netmon.firewall.FirewallDtos.TargetState;
import gov.mot.netmon.metrics.SnapshotStore;
import gov.mot.netmon.security.CurrentUser;
import gov.mot.netmon.server.Server;
import gov.mot.netmon.server.ServerRepository;
import gov.mot.netmon.user.RoleCode;

/** Firewall integrations (targets) and their dry-run / enforce mode. */
@Service
public class FirewallService {

    static final Set<String> RULE_ACTIVE = Set.of("APPLIED", "DRY_RUN", "UNVERIFIED", "APPROVED", "REMOVAL_PENDING", "REQUESTED");

    private final FirewallIntegrationRepository integrations;
    private final FirewallRuleRepository rules;
    private final ServerRepository servers;
    private final SnapshotStore snapshots;
    private final AuditService audit;
    private final Clock clock;
    private final AppProperties props;

    public FirewallService(FirewallIntegrationRepository integrations, FirewallRuleRepository rules, ServerRepository servers,
            SnapshotStore snapshots, AuditService audit, Clock clock, AppProperties props) {
        this.integrations = integrations;
        this.rules = rules;
        this.servers = servers;
        this.snapshots = snapshots;
        this.audit = audit;
        this.clock = clock;
        this.props = props;
    }

    /** Protected addresses: platform-wide setting plus what the target's agent reports (its platform address). */
    public List<String> protectedCidrs(FirewallIntegration i) {
        List<String> out = new ArrayList<>(Arrays.stream(props.firewall().protectedCidrs().split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList());
        TargetState t = target(i);
        if (t != null && t.protectedAddresses() != null) out.addAll(t.protectedAddresses());
        return out;
    }

    public TargetState target(FirewallIntegration i) {
        if (!i.isAgent()) return null;
        return snapshots.find(i.getServerId()).map(s -> {
            AgentProtocol.Firewall f = s.report().firewall();
            boolean online = s.receivedAt().isAfter(clock.instant().minus(props.monitoring().onlineThreshold()));
            if (f == null) return new TargetState(false, false, null, "The agent does not report firewall support (agent older than 0.6.0?)",
                    List.of(), s.receivedAt(), online, List.of());
            return new TargetState(f.enabled(), f.enforcementAllowed(), f.backend(), f.message(),
                    f.protectedAddresses() == null ? List.of() : f.protectedAddresses(), s.receivedAt(), online,
                    f.applicationControl() == null ? List.of() : f.applicationControl());
        }).orElse(new TargetState(false, false, null, "No report from an agent yet", List.of(), null, false, List.of()));
    }

    IntegrationView view(FirewallIntegration i) {
        Server s = i.getServerId() == null ? null : servers.findById(i.getServerId()).orElse(null);
        TargetState t = target(i);
        boolean enforcing = i.isAgent() && "ENFORCE".equals(i.getMode()) && t != null && t.enforcementAllowedOnServer();
        return new IntegrationView(i.getId(), i.getName(), i.getKind(), i.getServerId(), s == null ? null : s.getName(),
                s == null ? null : s.getEnvironment().name(), i.getMode(), i.getDescription(), i.getModeChangedBy(),
                i.getModeChangedAt(), t, rules.countByIntegrationIdAndStatusIn(i.getId(), RULE_ACTIVE), enforcing);
    }

    @Transactional(readOnly = true)
    public List<IntegrationView> list() {
        return integrations.findAllByOrderByNameAsc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public IntegrationView get(UUID id) {
        return view(find(id));
    }

    FirewallIntegration find(UUID id) {
        return integrations.findById(id).orElseThrow(() -> ApiException.notFound("Firewall integration"));
    }

    @Transactional
    public IntegrationView create(IntegrationRequest r) {
        String user = CurrentUser.require().username();
        Instant now = clock.instant();
        FirewallIntegration i;
        if (r.serverId() != null) {
            Server s = servers.findById(r.serverId()).orElseThrow(() -> ApiException.badRequest("UNKNOWN_SERVER", "Unknown server"));
            if (integrations.findByServerId(s.getId()).isPresent()) {
                throw ApiException.conflict("INTEGRATION_EXISTS", "This server already has a firewall integration");
            }
            i = new FirewallIntegration("Host firewall: " + s.getName(), "SERVER_AGENT", s.getId(), blank(r.description()), user, now);
        } else {
            String name = blank(r.name());
            if (name == null) throw ApiException.badRequest("NAME_REQUIRED", "Give the central firewall a name");
            if (integrations.existsByName(name)) throw ApiException.conflict("INTEGRATION_EXISTS", "This name is in use");
            i = new FirewallIntegration(name, "CENTRAL_DRY_RUN", null, blank(r.description()), user, now);
        }
        integrations.save(i);
        audit.event(AuditAction.FIREWALL_INTEGRATION_CREATED).target("FIREWALL_INTEGRATION", i.getId()).detail("name", i.getName())
                .detail("kind", i.getKind()).success();
        return view(i);
    }

    /**
     * Switching to ENFORCE needs a super administrator, an agent that allows enforcement locally, and
     * a reason; a central dry-run integration can never enforce.
     */
    @Transactional
    public IntegrationView setMode(UUID id, String mode, String reason) {
        FirewallIntegration i = find(id);
        if (!"DRY_RUN".equals(mode) && !"ENFORCE".equals(mode)) throw ApiException.badRequest("INVALID_MODE", "Mode must be DRY_RUN or ENFORCE");
        var user = CurrentUser.require();
        if ("ENFORCE".equals(mode)) {
            if (!user.hasRole(RoleCode.SUPER_ADMIN)) {
                throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "SUPER_ADMIN_REQUIRED",
                        "Only a super administrator can turn on real enforcement");
            }
            if (!i.isAgent()) throw ApiException.conflict("NOT_SUPPORTED", "No adapter exists for this central firewall: it stays in dry run");
            TargetState t = target(i);
            if (t == null || !t.agentFirewallEnabled() || !t.enforcementAllowedOnServer()) {
                throw ApiException.conflict("ENFORCEMENT_NOT_ALLOWED",
                        "The server's administrator has not allowed firewall enforcement on that server (firewall.enforcement=true)");
            }
            if (t.backend() == null || "NONE".equals(t.backend()) || "UNSUPPORTED".equals(t.backend())) {
                throw ApiException.conflict("NO_FIREWALL", "No supported firewall was detected on the server");
            }
        }
        String before = i.getMode();
        i.setMode(mode, user.username(), clock.instant());
        audit.event(AuditAction.FIREWALL_MODE_CHANGED).target("FIREWALL_INTEGRATION", id).detail("name", i.getName())
                .detail("from", before).detail("to", mode).reason(reason).success();
        return view(i);
    }

    @Transactional
    public void delete(UUID id) {
        FirewallIntegration i = find(id);
        if (rules.countByIntegrationIdAndStatusIn(id, Set.of("APPLIED", "UNVERIFIED", "APPROVED", "REMOVAL_PENDING")) > 0) {
            throw ApiException.conflict("RULES_ACTIVE", "Remove the rules applied on this target first");
        }
        integrations.delete(i);
        audit.event(AuditAction.FIREWALL_INTEGRATION_DELETED).target("FIREWALL_INTEGRATION", id).detail("name", i.getName()).success();
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    Duration taskTimeout() {
        return props.firewall().taskTimeout();
    }
}
