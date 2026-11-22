package gov.mot.netmon.internet;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.agent.AgentProtocol;
import gov.mot.netmon.audit.AuditAction;
import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.firewall.AppDtos;
import gov.mot.netmon.firewall.ApplicationService;
import gov.mot.netmon.firewall.FirewallDtos.IntegrationRequest;
import gov.mot.netmon.firewall.FirewallIntegrationRepository;
import gov.mot.netmon.firewall.FirewallRule;
import gov.mot.netmon.firewall.FirewallRuleRepository;
import gov.mot.netmon.firewall.FirewallRuleService;
import gov.mot.netmon.firewall.FirewallService;
import gov.mot.netmon.internet.InternetDtos.AppSwitch;
import gov.mot.netmon.internet.InternetDtos.Apps;
import gov.mot.netmon.internet.InternetDtos.Computer;
import gov.mot.netmon.internet.InternetDtos.InternetView;
import gov.mot.netmon.internet.InternetDtos.SiteView;
import gov.mot.netmon.internet.InternetDtos.Sites;
import gov.mot.netmon.metrics.SnapshotStore;
import gov.mot.netmon.security.CurrentUser;
import gov.mot.netmon.server.Server;
import gov.mot.netmon.server.ServerEnums.OsFamily;
import gov.mot.netmon.server.ServerRepository;

/**
 * Internet control: simple on/off switches for websites (blocked by name through the computer's
 * hosts file) and for applications' internet access (a "block internet" application policy).
 * Application switches use the normal policy workflow; with PERSONAL_MODE they apply at once.
 */
@Service
public class InternetControlService {

    static final int MAX_SITES = 200;
    /** Same rule as the agent and the Linux helper: a plain host name with at least one dot. */
    static final Pattern DOMAIN = Pattern.compile("^(?=.{3,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$");
    /** Policy statuses in which the application's internet is (or is about to be) cut. */
    static final Set<String> BLOCKING = Set.of("REQUESTED", "APPROVED", "APPLIED", "DRY_RUN", "UNVERIFIED");
    static final String SWITCH_REASON_OFF = "Internet switched off in Internet control";
    static final String SWITCH_REASON_ON = "Internet switched on in Internet control";

    private final BlockedSiteRepository sites;
    private final ServerRepository servers;
    private final SnapshotStore snapshots;
    private final ApplicationService applications;
    private final FirewallService firewalls;
    private final FirewallIntegrationRepository integrations;
    private final FirewallRuleRepository rules;
    private final FirewallRuleService ruleService;
    private final AuditService audit;
    private final AppProperties props;
    private final Clock clock;

    public InternetControlService(BlockedSiteRepository sites, ServerRepository servers, SnapshotStore snapshots,
            ApplicationService applications, FirewallService firewalls, FirewallIntegrationRepository integrations,
            FirewallRuleRepository rules, FirewallRuleService ruleService, AuditService audit, AppProperties props, Clock clock) {
        this.sites = sites;
        this.servers = servers;
        this.snapshots = snapshots;
        this.applications = applications;
        this.firewalls = firewalls;
        this.integrations = integrations;
        this.rules = rules;
        this.ruleService = ruleService;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public List<Computer> computers() {
        Instant now = clock.instant();
        return servers.findAll().stream()
                .filter(s -> s.getOsFamily() != OsFamily.OTHER)
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(s -> new Computer(s.getId(), s.getName(), s.getOsFamily().name(),
                        s.connectionStatus(now, props.monitoring().onlineThreshold()).name()))
                .toList();
    }

    @Transactional(readOnly = true)
    public InternetView view(UUID serverId) {
        return build(serverId);
    }

    /** Not a read-only transaction: also used at the end of the write methods. */
    private InternetView build(UUID serverId) {
        Server s = server(serverId);
        return new InternetView(s.getId(), s.getName(), s.getOsFamily().name(),
                s.connectionStatus(clock.instant(), props.monitoring().onlineThreshold()).name(), firewalls.personalMode(),
                sitesOf(s), appsOf(s));
    }

    private Server server(UUID id) {
        return servers.findById(id).orElseThrow(() -> ApiException.notFound("Computer"));
    }

    private Sites sitesOf(Server s) {
        AgentProtocol.Sites reported = snapshots.find(s.getId()).map(x -> x.report().sites()).orElse(null);
        boolean enabled = reported != null && reported.enabled();
        Set<String> present = new HashSet<>(reported == null || reported.blocked() == null ? List.of() : reported.blocked());
        String message = reported != null ? reported.message()
                : "This computer's agent does not report website blocking yet (agent 0.9.0 or newer, with sites.enabled=true)";
        List<SiteView> list = sites.findByServerIdOrderByDomainAsc(s.getId()).stream().map(b -> {
            String state;
            if (!enabled) state = b.isBlocked() ? "OFF_ON_COMPUTER" : "ALLOWED";
            else if (b.isBlocked()) state = present.contains(b.getDomain()) ? "BLOCKED" : "BLOCKING";
            else state = present.contains(b.getDomain()) ? "UNBLOCKING" : "ALLOWED";
            return new SiteView(b.getId(), b.getDomain(), b.isBlocked(), state, b.getUpdatedBy(), b.getUpdatedAt());
        }).toList();
        return new Sites(enabled, message, list);
    }

    static String identityKind(Server s) {
        return switch (s.getOsFamily()) {
            case WINDOWS -> "WINDOWS_PROGRAM";
            case LINUX -> "LINUX_USER";
            default -> null;
        };
    }

    private Apps appsOf(Server s) {
        String kind = identityKind(s);
        var target = integrations.findByServerId(s.getId()).orElse(null);
        AgentProtocol.Firewall fw = snapshots.find(s.getId()).map(x -> x.report().firewall()).orElse(null);
        boolean fwOn = fw != null && fw.enabled();
        boolean kindOk = kind != null && fwOn && fw.applicationControl() != null && fw.applicationControl().contains(kind);
        // Without a target yet (it is created at the first switch) a new target would enforce if the
        // computer allows it and PERSONAL_MODE is on; new targets start in dry run otherwise.
        boolean enforcing = target != null ? firewalls.enforces(target, firewalls.target(target))
                : fwOn && fw.enforcementAllowed() && firewalls.personalMode();
        String why = null;
        if (kind == null) why = "Internet switches work on Windows and Linux computers only";
        else if (!fwOn) why = "Firewall control is off on this computer (firewall.enabled=true in agent.properties)";
        else if (!kindOk) why = "This computer's firewall (" + fw.backend() + ") cannot switch applications";
        else if (!enforcing) why = firewalls.personalMode()
                ? "This computer does not allow real changes (firewall.enforcement=true in agent.properties): switches are only checked"
                : "This computer is in dry run: switches are checked but nothing is blocked until a super administrator sets it to Enforce";
        List<AppSwitch> list = applications.list(s.getId()).stream().map(this::switchOf).toList();
        return new Apps(kindOk, why, kind, enforcing, list);
    }

    private AppSwitch switchOf(AppDtos.AppView a) {
        FirewallRule latest = rules.findByApplicationIdOrderByRequestedAtDesc(a.id()).stream()
                .filter(r -> "BLOCK_INTERNET".equals(r.getAppMode())).findFirst().orElse(null);
        boolean blocked = latest != null && BLOCKING.contains(latest.getStatus());
        return new AppSwitch(a.id(), a.name(), a.identityKind(), a.identityValue(), blocked,
                latest == null ? null : latest.getStatus(), latest == null ? null : latest.getStatusMessage(),
                latest == null ? null : latest.getId(), a.seenProcesses());
    }

    // ------------------------------------------------------------------ websites

    /** "https://www.YouTube.com/watch?v=1" -> "youtube.com". Null if it is not a website name. */
    static String normalize(String input) {
        if (input == null) return null;
        String d = input.trim().toLowerCase(Locale.ROOT);
        int scheme = d.indexOf("://");
        if (scheme >= 0) d = d.substring(scheme + 3);
        for (char c : new char[] {'/', '?', '#'}) {
            int i = d.indexOf(c);
            if (i >= 0) d = d.substring(0, i);
        }
        int at = d.lastIndexOf('@');
        if (at >= 0) d = d.substring(at + 1);
        int colon = d.indexOf(':');
        if (colon >= 0) d = d.substring(0, colon);
        while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        if (d.startsWith("www.")) d = d.substring(4);
        return DOMAIN.matcher(d).matches() ? d : null;
    }

    @Transactional
    public InternetView addSite(UUID serverId, String input) {
        Server s = server(serverId);
        String domain = normalize(input);
        if (domain == null) {
            throw ApiException.badRequest("INVALID_SITE", "Enter a website name such as youtube.com (not an IP address or a pattern)");
        }
        String user = CurrentUser.require().username();
        Instant now = clock.instant();
        BlockedSite site = sites.findByServerIdAndDomain(s.getId(), domain).orElse(null);
        if (site == null) {
            if (sites.countByServerId(s.getId()) >= MAX_SITES) {
                throw ApiException.conflict("TOO_MANY_SITES", "At most " + MAX_SITES + " websites per computer");
            }
            site = sites.save(new BlockedSite(s.getId(), domain, user, now));
        } else {
            site.setBlocked(true, user, now);
        }
        audit.event(AuditAction.WEBSITE_BLOCKED).target("SERVER", s.getId()).detail("server", s.getName()).detail("site", domain).success();
        return build(serverId);
    }

    @Transactional
    public InternetView switchSite(UUID siteId, boolean blocked) {
        BlockedSite site = sites.findById(siteId).orElseThrow(() -> ApiException.notFound("Website"));
        site.setBlocked(blocked, CurrentUser.require().username(), clock.instant());
        audit.event(blocked ? AuditAction.WEBSITE_BLOCKED : AuditAction.WEBSITE_UNBLOCKED).target("SERVER", site.getServerId())
                .detail("site", site.getDomain()).success();
        return build(site.getServerId());
    }

    @Transactional
    public InternetView removeSite(UUID siteId) {
        BlockedSite site = sites.findById(siteId).orElseThrow(() -> ApiException.notFound("Website"));
        UUID serverId = site.getServerId();
        sites.delete(site);
        audit.event(AuditAction.WEBSITE_REMOVED).target("SERVER", serverId).detail("site", site.getDomain()).success();
        return build(serverId);
    }

    // ------------------------------------------------------------------ applications

    @Transactional
    public InternetView addApp(UUID serverId, String name, String identity) {
        Server s = server(serverId);
        String kind = identityKind(s);
        if (kind == null) throw ApiException.badRequest("UNSUPPORTED_OS", "Internet switches work on Windows and Linux computers only");
        applications.create(new AppDtos.AppRequest(s.getId(), name.trim(), null, null, kind, identity.trim()));
        return build(serverId);
    }

    /**
     * internet=false: request a "block internet" policy (approved at once in PERSONAL_MODE).
     * internet=true: remove the active policy, or cancel it while it still waits for approval.
     */
    @Transactional
    public InternetView switchApp(UUID appId, boolean internet) {
        AppDtos.AppView app = applications.get(appId);
        List<FirewallRule> active = rules.findByApplicationIdOrderByRequestedAtDesc(appId).stream()
                .filter(r -> "BLOCK_INTERNET".equals(r.getAppMode()) && BLOCKING.contains(r.getStatus())).toList();
        if (internet) {
            for (FirewallRule r : active) {
                if ("REQUESTED".equals(r.getStatus())) ruleService.cancel(r.getId());
                else ruleService.remove(r.getId(), SWITCH_REASON_ON);
            }
        } else if (active.isEmpty()) {
            if (integrations.findByServerId(app.serverId()).isEmpty()) {
                firewalls.create(new IntegrationRequest(app.serverId(), null, "Created by Internet control"));
            }
            applications.request(new AppDtos.PolicyRequest(appId, "BLOCK_INTERNET", List.of(), SWITCH_REASON_OFF, null, null, true));
        }
        return build(app.serverId());
    }
}
