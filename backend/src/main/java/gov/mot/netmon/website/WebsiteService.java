package gov.mot.netmon.website;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.agent.AgentProtocol;
import gov.mot.netmon.audit.AuditAction;
import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.security.CurrentUser;
import gov.mot.netmon.server.Server;
import gov.mot.netmon.server.ServerRepository;
import gov.mot.netmon.website.WebsiteDtos.DeviceStatus;
import gov.mot.netmon.website.WebsiteDtos.DeviceView;
import gov.mot.netmon.website.WebsiteDtos.Overview;
import gov.mot.netmon.website.WebsiteDtos.SiteView;
import gov.mot.netmon.website.WebsiteDtos.Summary;

/**
 * Website blocking with one on/off switch per website, for every device (server or staff PC) whose
 * agent has website blocking enabled locally. The blocked list travels with each agent's settings;
 * each agent writes it into the device's hosts file and reports back what is really there, so the
 * status shown is always what the devices confirmed.
 */
@Service
public class WebsiteService {

    static final int MAX_SITES = 500;
    static final String OLD_AGENT = "This device's agent cannot block websites yet: update the agent.";

    private final JdbcTemplate jdbc;
    private final ServerRepository servers;
    private final AuditService audit;
    private final Clock clock;
    private final AppProperties props;

    public WebsiteService(JdbcTemplate jdbc, ServerRepository servers, AuditService audit, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.servers = servers;
        this.audit = audit;
        this.clock = clock;
        this.props = props;
    }

    // ------------------------------------------------------------------ agents

    /** Base domains every enabled device must block, sent with the agent settings. */
    @Transactional(readOnly = true)
    public List<String> blockedDomains() {
        return jdbc.queryForList("SELECT domain FROM blocked_websites WHERE blocked ORDER BY domain", String.class);
    }

    /** Records what the device reported (also while its metrics are paused). Older reports never overwrite newer ones. */
    @Transactional
    public void onReport(Server server, AgentProtocol.Report report) {
        AgentProtocol.Websites w = report.websites();
        boolean enabled = w != null && w.enabled();
        String message = w == null ? OLD_AGENT : w.message();
        String blocked = w == null || w.blocked() == null ? "" : w.blocked().stream().limit(1000).collect(Collectors.joining("\n"));
        jdbc.update("""
                INSERT INTO device_website_state (server_id, enabled, message, blocked, collected_at, received_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (server_id) DO UPDATE SET enabled = EXCLUDED.enabled, message = EXCLUDED.message,
                    blocked = EXCLUDED.blocked, collected_at = EXCLUDED.collected_at, received_at = EXCLUDED.received_at
                WHERE device_website_state.collected_at <= EXCLUDED.collected_at""",
                server.getId(), enabled, message, blocked, Timestamp.from(report.collectedAt()), Timestamp.from(clock.instant()));
    }

    // ------------------------------------------------------------------ queries

    record Site(UUID id, String domain, boolean blocked, String note, String createdBy, Instant createdAt, String updatedBy,
            Instant updatedAt) {
    }

    record DeviceState(boolean enabled, String message, Set<String> blocked, Instant collectedAt, Instant receivedAt) {
    }

    private List<Site> sites() {
        return jdbc.query("SELECT id, domain, blocked, note, created_by, created_at, updated_by, updated_at FROM blocked_websites ORDER BY domain",
                (rs, n) -> new Site(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3), rs.getString(4), rs.getString(5),
                        rs.getTimestamp(6).toInstant(), rs.getString(7), rs.getTimestamp(8).toInstant()));
    }

    private Map<UUID, DeviceState> states() {
        Map<UUID, DeviceState> out = new HashMap<>();
        jdbc.query("SELECT server_id, enabled, message, blocked, collected_at, received_at FROM device_website_state", rs -> {
            String b = rs.getString(4);
            out.put(rs.getObject(1, UUID.class), new DeviceState(rs.getBoolean(2), rs.getString(3),
                    b == null || b.isEmpty() ? Set.of() : Set.copyOf(Arrays.asList(b.split("\n"))),
                    rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant()));
        });
        return out;
    }

    static String state(Site site, DeviceState d, boolean online) {
        if (d == null || !d.enabled()) return "NOT_SET_UP";
        boolean present = d.blocked().contains(site.domain());
        if (site.blocked() == present) return site.blocked() ? "BLOCKED" : "ALLOWED";
        return online ? "WAITING" : "OFFLINE";
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        Instant now = clock.instant();
        Duration threshold = props.monitoring().onlineThreshold();
        Map<UUID, DeviceState> states = states();
        List<Server> all = servers.findAll().stream().sorted(Comparator.comparing(Server::getName, String.CASE_INSENSITIVE_ORDER)).toList();
        List<DeviceView> devices = new ArrayList<>();
        Map<UUID, Boolean> online = new HashMap<>();
        for (Server s : all) {
            DeviceState d = states.get(s.getId());
            boolean on = d != null && d.receivedAt().isAfter(now.minus(threshold));
            online.put(s.getId(), on);
            devices.add(new DeviceView(s.getId(), s.getName(), s.getOsFamily().name(), d != null && d.enabled(), on,
                    d == null ? null : d.message(), d == null ? null : d.collectedAt()));
        }
        List<SiteView> views = new ArrayList<>();
        for (Site site : sites()) {
            List<DeviceStatus> per = new ArrayList<>();
            int done = 0, waiting = 0, offline = 0, notSetUp = 0;
            for (Server s : all) {
                String st = state(site, states.get(s.getId()), online.get(s.getId()));
                switch (st) {
                    case "BLOCKED", "ALLOWED" -> done++;
                    case "WAITING" -> waiting++;
                    case "OFFLINE" -> offline++;
                    default -> notSetUp++;
                }
                per.add(new DeviceStatus(s.getId(), s.getName(), st));
            }
            views.add(new SiteView(site.id(), site.domain(), WebsiteNames.namesOnDevice(site.domain()), site.blocked(), site.note(),
                    site.createdBy(), site.createdAt(), site.updatedBy(), site.updatedAt(),
                    new Summary(all.size(), done, waiting, offline, notSetUp), per));
        }
        return new Overview(views, devices);
    }

    // ------------------------------------------------------------------ changes

    /** The platform's own host name: blocking it would cut every agent off from NetMon. */
    String platformHost() {
        String url = props.agents().publicUrl();
        if (url == null || url.isBlank()) return null;
        try {
            String h = URI.create(url.trim()).getHost();
            return h == null ? null : h.toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Transactional
    public Overview add(String website, String note) {
        String user = CurrentUser.require().username();
        String domain = WebsiteNames.normalize(website);
        if (domain == null) {
            throw ApiException.badRequest("INVALID_WEBSITE", "Type a website name such as facebook.com (not an IP address)");
        }
        String platform = platformHost();
        if (platform != null && WebsiteNames.namesOnDevice(domain).contains(platform)) {
            throw ApiException.badRequest("PROTECTED_WEBSITE", "This is NetMon's own address: blocking it would cut every device off from NetMon");
        }
        Integer count = jdbc.queryForObject("SELECT count(*) FROM blocked_websites", Integer.class);
        if (count != null && count >= MAX_SITES) throw ApiException.conflict("TOO_MANY_WEBSITES", "At most " + MAX_SITES + " websites");
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        String n = note == null || note.isBlank() ? null : note.trim();
        try {
            jdbc.update("INSERT INTO blocked_websites (id, domain, blocked, note, created_by, created_at, updated_by, updated_at) "
                    + "VALUES (?, ?, TRUE, ?, ?, ?, ?, ?)", id, domain, n, user, Timestamp.from(now), user, Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("WEBSITE_EXISTS", domain + " is already in the list");
        }
        audit.event(AuditAction.WEBSITE_ADDED).target("WEBSITE", id).detail("website", domain).detail("blocked", true).success();
        return overview();
    }

    @Transactional
    public Overview setBlocked(UUID id, boolean blocked) {
        String user = CurrentUser.require().username();
        String domain = domain(id);
        int changed = jdbc.update("UPDATE blocked_websites SET blocked = ?, updated_by = ?, updated_at = ? WHERE id = ? AND blocked <> ?",
                blocked, user, Timestamp.from(clock.instant()), id, blocked);
        if (changed > 0) {
            audit.event(AuditAction.WEBSITE_SWITCHED).target("WEBSITE", id).detail("website", domain).detail("blocked", blocked).success();
        }
        return overview();
    }

    @Transactional
    public Overview delete(UUID id) {
        String domain = domain(id);
        jdbc.update("DELETE FROM blocked_websites WHERE id = ?", id);
        audit.event(AuditAction.WEBSITE_DELETED).target("WEBSITE", id).detail("website", domain).success();
        return overview();
    }

    private String domain(UUID id) {
        List<String> d = jdbc.queryForList("SELECT domain FROM blocked_websites WHERE id = ?", String.class, id);
        if (d.isEmpty()) throw ApiException.notFound("Website");
        return d.get(0);
    }
}
