package gov.mot.netmon.network;

import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.audit.AuditAction;
import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.common.Cidr;
import gov.mot.netmon.common.IpAddresses;
import gov.mot.netmon.common.PageResponse;
import gov.mot.netmon.event.EventAccess;
import gov.mot.netmon.network.NetworkDtos.AlertRef;
import gov.mot.netmon.network.NetworkDtos.CaptureConversation;
import gov.mot.netmon.network.NetworkDtos.FlowSummary;
import gov.mot.netmon.network.NetworkDtos.ImportResult;
import gov.mot.netmon.network.NetworkDtos.IpDetail;
import gov.mot.netmon.network.NetworkDtos.IpRequest;
import gov.mot.netmon.network.NetworkDtos.IpView;
import gov.mot.netmon.network.NetworkDtos.Peer;
import gov.mot.netmon.network.NetworkDtos.Seen;
import gov.mot.netmon.network.NetworkDtos.SubnetRequest;
import gov.mot.netmon.network.NetworkDtos.SubnetView;
import gov.mot.netmon.security.AuthenticatedUser;
import gov.mot.netmon.security.CurrentUser;

/** Subnets and the IP address inventory. */
@Service
public class IpInventoryService {

    static final Duration SEEN_NOW = Duration.ofMinutes(15);
    static final Duration SEEN_RECENTLY = Duration.ofDays(7);
    static final Set<String> ALLOCATIONS = Set.of("UNKNOWN", "ASSIGNED", "RESERVED", "DHCP", "GATEWAY");
    static final int MAX_IMPORT_LINES = 5000;

    private final SubnetRepository subnets;
    private final NamedParameterJdbcTemplate jdbc;
    private final IpObservations observations;
    private final AuditService audit;
    private final Clock clock;

    public IpInventoryService(SubnetRepository subnets, NamedParameterJdbcTemplate jdbc, IpObservations observations,
            AuditService audit, Clock clock) {
        this.subnets = subnets;
        this.jdbc = jdbc;
        this.observations = observations;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- subnets

    @Transactional(readOnly = true)
    public List<SubnetView> subnets() {
        Map<UUID, long[]> counts = new HashMap<>();
        jdbc.query("SELECT subnet_id, count(*) AS n, count(*) FILTER (WHERE last_seen_at > :day) AS seen FROM ip_addresses "
                + "WHERE subnet_id IS NOT NULL GROUP BY subnet_id",
                new MapSqlParameterSource("day", Timestamp.from(clock.instant().minus(Duration.ofDays(1)))),
                rs -> {
                    counts.put(rs.getObject(1, UUID.class), new long[] {rs.getLong("n"), rs.getLong("seen")});
                });
        return subnets.findAllByOrderByIpVersionAscCidrAsc().stream().map(s -> view(s, counts.getOrDefault(s.getId(), new long[2])))
                .toList();
    }

    @Transactional(readOnly = true)
    public SubnetView subnet(UUID id) {
        return subnets().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Subnet"));
    }

    private SubnetView view(Subnet s, long[] c) {
        Cidr cidr = Cidr.parse(s.getCidr());
        BigInteger usable = cidr.usable();
        Double pct = s.getIpVersion() == 4 && usable.signum() > 0
                ? Math.min(100.0, Math.round(c[0] * 1000.0 / usable.doubleValue()) / 10.0) : null;
        return new SubnetView(s.getId(), s.getCidr(), s.getIpVersion(), s.getPrefix(), s.getName(), s.getDescription(),
                s.getVlanId(), s.getGateway(), s.getLocation(), s.getOrganization(), cidr.size().toString(), usable.toString(),
                c[0], c[1], pct, s.getUpdatedAt(), s.getUpdatedBy());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Cidr validate(SubnetRequest r) {
        Cidr c;
        try {
            c = Cidr.parse(r.cidr());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_CIDR", e.getMessage());
        }
        if (c.version() == 4 && c.prefix() < 8 || c.version() == 6 && c.prefix() < 16) {
            throw ApiException.badRequest("INVALID_CIDR", "The network is too large to manage as one subnet");
        }
        if (blank(r.gateway()) != null) {
            try {
                if (!c.contains(r.gateway())) throw ApiException.badRequest("GATEWAY_OUTSIDE", "The gateway is not inside the subnet");
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("INVALID_ADDRESS", e.getMessage());
            }
        }
        return c;
    }

    @Transactional
    public SubnetView createSubnet(SubnetRequest r) {
        Cidr c = validate(r);
        if (subnets.existsByCidr(c.network())) throw ApiException.conflict("SUBNET_EXISTS", "This subnet is already registered");
        String user = CurrentUser.require().username();
        Subnet s = new Subnet(user, clock.instant());
        apply(s, r, c, user);
        subnets.saveAndFlush(s);
        observations.reassignSubnets();
        audit.event(AuditAction.SUBNET_CREATED).target("SUBNET", s.getId()).detail("cidr", s.getCidr()).detail("name", s.getName())
                .success();
        return subnet(s.getId());
    }

    @Transactional
    public SubnetView updateSubnet(UUID id, SubnetRequest r) {
        Subnet s = subnets.findById(id).orElseThrow(() -> ApiException.notFound("Subnet"));
        Cidr c = validate(r);
        if (!c.network().equals(s.getCidr()) && subnets.existsByCidr(c.network())) {
            throw ApiException.conflict("SUBNET_EXISTS", "This subnet is already registered");
        }
        String before = s.getCidr() + " " + s.getName();
        apply(s, r, c, CurrentUser.require().username());
        subnets.saveAndFlush(s);
        observations.reassignSubnets();
        audit.event(AuditAction.SUBNET_UPDATED).target("SUBNET", id).detail("before", before)
                .detail("after", s.getCidr() + " " + s.getName()).success();
        return subnet(id);
    }

    private void apply(Subnet s, SubnetRequest r, Cidr c, String user) {
        s.apply(c.network(), c.version(), c.prefix(), r.name().trim(), blank(r.description()), r.vlanId(),
                blank(r.gateway()) == null ? null : IpAddresses.parse(r.gateway()).canonical(), blank(r.location()),
                blank(r.organization()), user, clock.instant());
    }

    @Transactional
    public void deleteSubnet(UUID id) {
        Subnet s = subnets.findById(id).orElseThrow(() -> ApiException.notFound("Subnet"));
        subnets.delete(s);
        subnets.flush();
        observations.reassignSubnets();
        audit.event(AuditAction.SUBNET_DELETED).target("SUBNET", id).detail("cidr", s.getCidr()).success();
    }

    // ---------------------------------------------------------------- addresses

    private static final String SELECT = """
            SELECT i.*, s.cidr AS subnet_cidr, s.name AS subnet_name, sv.name AS server_name, d.name AS device_name
            FROM ip_addresses i LEFT JOIN subnets s ON s.id = i.subnet_id LEFT JOIN servers sv ON sv.id = i.server_id
            LEFT JOIN network_devices d ON d.id = i.device_id""";

    @Transactional(readOnly = true)
    public PageResponse<IpView> search(String q, UUID subnetId, boolean noSubnet, String source, Seen seen, UUID serverId, int page,
            int size) {
        if (page < 0 || size < 1 || size > 500) throw ApiException.badRequest("INVALID_PAGE", "Invalid page");
        MapSqlParameterSource p = new MapSqlParameterSource();
        List<String> w = new ArrayList<>();
        if (blank(q) != null) {
            w.add("(i.address ILIKE :q OR i.hostname ILIKE :q OR i.mac_address ILIKE :q)");
            p.addValue("q", "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (subnetId != null) {
            w.add("i.subnet_id = :subnet");
            p.addValue("subnet", subnetId);
        }
        if (noSubnet) w.add("i.subnet_id IS NULL");
        if (blank(source) != null) {
            w.add("(',' || i.sources || ',') LIKE :src");
            p.addValue("src", "%," + source.trim().toUpperCase(Locale.ROOT) + ",%");
        }
        if (serverId != null) {
            w.add("i.server_id = :server");
            p.addValue("server", serverId);
        }
        Instant now = clock.instant();
        p.addValue("now15", Timestamp.from(now.minus(SEEN_NOW))).addValue("week", Timestamp.from(now.minus(SEEN_RECENTLY)));
        if (seen != null) {
            w.add(switch (seen) {
                case SEEN_NOW -> "i.last_seen_at > :now15";
                case SEEN_RECENTLY -> "i.last_seen_at <= :now15 AND i.last_seen_at > :week";
                case NOT_SEEN_RECENTLY -> "i.last_seen_at <= :week";
                case NEVER_SEEN -> "i.last_seen_at IS NULL";
            });
        }
        String where = w.isEmpty() ? "" : " WHERE " + String.join(" AND ", w);
        Long total = jdbc.queryForObject("SELECT count(*) FROM ip_addresses i" + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<IpView> rows = jdbc.query(SELECT + where + " ORDER BY i.ip_version, i.address::inet LIMIT :limit OFFSET :offset", p,
                this::map);
        long t = total == null ? 0 : total;
        return new PageResponse<>(rows, page, size, t, (int) ((t + size - 1) / size));
    }

    private IpView map(ResultSet rs, int i) throws SQLException {
        Timestamp last = rs.getTimestamp("last_seen_at");
        Timestamp first = rs.getTimestamp("first_seen_at");
        Instant now = clock.instant();
        Seen seen = last == null ? Seen.NEVER_SEEN
                : last.toInstant().isAfter(now.minus(SEEN_NOW)) ? Seen.SEEN_NOW
                : last.toInstant().isAfter(now.minus(SEEN_RECENTLY)) ? Seen.SEEN_RECENTLY : Seen.NOT_SEEN_RECENTLY;
        String src = rs.getString("sources");
        return new IpView(rs.getObject("id", UUID.class), rs.getString("address"), rs.getInt("ip_version"),
                rs.getObject("subnet_id", UUID.class), rs.getString("subnet_cidr"), rs.getString("subnet_name"),
                rs.getObject("server_id", UUID.class), rs.getString("server_name"), rs.getObject("device_id", UUID.class),
                rs.getString("device_name"), rs.getString("mac_address"), rs.getString("hostname"), rs.getString("allocation"),
                src == null || src.isEmpty() ? List.of() : Arrays.asList(src.split(",")),
                first == null ? null : first.toInstant(), last == null ? null : last.toInstant(), rs.getString("last_seen_source"),
                seen, rs.getString("notes"));
    }

    private IpView find(UUID id) {
        List<IpView> l = jdbc.query(SELECT + " WHERE i.id = :id", new MapSqlParameterSource("id", id), this::map);
        if (l.isEmpty()) throw ApiException.notFound("IP address");
        return l.get(0);
    }

    @Transactional(readOnly = true)
    public IpDetail detail(UUID id) {
        IpView ip = find(id);
        AuthenticatedUser u = CurrentUser.require();
        Set<gov.mot.netmon.event.EventCategory> hidden = EventAccess.hiddenCategories();
        MapSqlParameterSource p = new MapSqlParameterSource("ip", ip.address()).addValue("server", ip.serverId())
                .addValue("hidden", hidden.isEmpty() ? List.of("-") : hidden.stream().map(Enum::name).toList());
        List<AlertRef> alerts = jdbc.query("""
                SELECT id, rule_name, status, severity, resource, last_triggered_at FROM alerts
                WHERE (resource = :ip OR (server_id = :server AND status <> 'RESOLVED')) AND category NOT IN (:hidden)
                ORDER BY last_triggered_at DESC LIMIT 20""", p,
                (rs, i) -> new AlertRef(rs.getObject("id", UUID.class), rs.getString("rule_name"), rs.getString("status"),
                        rs.getString("severity"), rs.getString("resource"), rs.getTimestamp("last_triggered_at").toInstant()));
        boolean traffic = NetworkAccess.canSeeTraffic(u);
        List<CaptureConversation> captures = List.of();
        FlowSummary flows = null;
        if (traffic) {
            captures = jdbc.query("""
                    SELECT c.id, c.name, count(*) AS n, min(ps.captured_at) AS f, max(ps.captured_at) AS l
                    FROM packet_summaries ps JOIN packet_captures c ON c.id = ps.capture_id
                    WHERE ps.src = :ip OR ps.dst = :ip GROUP BY c.id, c.name ORDER BY max(ps.captured_at) DESC LIMIT 20""", p,
                    (rs, i) -> new CaptureConversation(rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("n"),
                            rs.getTimestamp("f").toInstant(), rs.getTimestamp("l").toInstant()));
            Instant to = clock.instant();
            Instant from = to.minus(Duration.ofDays(1));
            p.addValue("from", Timestamp.from(from));
            long[] totals = new long[3];
            jdbc.query("""
                    SELECT COALESCE(sum(bytes) FILTER (WHERE src_ip = :ip::inet), 0) AS sent,
                           COALESCE(sum(bytes) FILTER (WHERE dst_ip = :ip::inet), 0) AS recv, count(*) AS n
                    FROM network_flows WHERE (src_ip = :ip::inet OR dst_ip = :ip::inet) AND flow_end > :from""", p, rs -> {
                totals[0] = rs.getLong("sent");
                totals[1] = rs.getLong("recv");
                totals[2] = rs.getLong("n");
            });
            List<Peer> peers = jdbc.query("""
                    SELECT host(CASE WHEN src_ip = :ip::inet THEN dst_ip ELSE src_ip END) AS peer, sum(bytes) AS b, count(*) AS n
                    FROM network_flows WHERE (src_ip = :ip::inet OR dst_ip = :ip::inet) AND flow_end > :from
                    GROUP BY 1 ORDER BY 2 DESC LIMIT 10""", p,
                    (rs, i) -> new Peer(rs.getString("peer"), rs.getLong("b"), rs.getLong("n")));
            flows = new FlowSummary(from, to, totals[0], totals[1], totals[2], peers);
        }
        return new IpDetail(ip, alerts, captures, !traffic, flows, !traffic);
    }

    private static String mac(String raw) {
        if (blank(raw) == null) return null;
        String m = IpObservations.mac(raw);
        if (m == null) throw ApiException.badRequest("INVALID_MAC", "MAC address must look like aa:bb:cc:dd:ee:ff");
        return m;
    }

    private static String allocation(String raw) {
        String a = blank(raw) == null ? "UNKNOWN" : raw.trim().toUpperCase(Locale.ROOT);
        if (!ALLOCATIONS.contains(a)) throw ApiException.badRequest("INVALID_ALLOCATION", "Unknown allocation");
        return a;
    }

    private static IpAddresses.ParsedIp parseIp(String raw) {
        try {
            return IpAddresses.parse(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_ADDRESS", e.getMessage());
        }
    }

    /** Insert or update one address with values entered by a person (they override discovered values). */
    private boolean upsertManual(IpAddresses.ParsedIp ip, String mac, String hostname, String allocation, String notes,
            IpSource source) {
        Boolean inserted = jdbc.queryForObject("""
                INSERT INTO ip_addresses (id, address, ip_version, subnet_id, mac_address, hostname, allocation, notes, sources,
                    created_at, updated_at)
                VALUES (:id, :a, :v, (SELECT s.id FROM subnets s WHERE :a::inet <<= s.cidr::inet ORDER BY s.prefix DESC LIMIT 1),
                    :mac, :host, :alloc, :notes, :src, now(), now())
                ON CONFLICT (address) DO UPDATE SET
                    mac_address = COALESCE(EXCLUDED.mac_address, ip_addresses.mac_address),
                    hostname = COALESCE(EXCLUDED.hostname, ip_addresses.hostname),
                    allocation = EXCLUDED.allocation,
                    notes = COALESCE(EXCLUDED.notes, ip_addresses.notes),
                    sources = CASE WHEN (',' || ip_addresses.sources || ',') LIKE ('%,' || EXCLUDED.sources || ',%') THEN ip_addresses.sources
                                   WHEN ip_addresses.sources = '' THEN EXCLUDED.sources
                                   ELSE ip_addresses.sources || ',' || EXCLUDED.sources END,
                    updated_at = now()
                RETURNING (xmax = 0)""",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("a", ip.canonical()).addValue("v", ip.version())
                        .addValue("mac", mac).addValue("host", hostname).addValue("alloc", allocation).addValue("notes", notes)
                        .addValue("src", source.name()), Boolean.class);
        return Boolean.TRUE.equals(inserted);
    }

    @Transactional
    public IpView create(IpRequest r) {
        IpAddresses.ParsedIp ip = parseIp(r.address());
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM ip_addresses WHERE address = :a",
                new MapSqlParameterSource("a", ip.canonical()), Integer.class);
        if (exists != null && exists > 0) throw ApiException.conflict("ADDRESS_EXISTS", "This address is already in the inventory");
        upsertManual(ip, mac(r.macAddress()), blank(r.hostname()), allocation(r.allocation()), blank(r.notes()), IpSource.MANUAL);
        UUID id = jdbc.queryForObject("SELECT id FROM ip_addresses WHERE address = :a", new MapSqlParameterSource("a", ip.canonical()),
                UUID.class);
        audit.event(AuditAction.IP_ADDRESS_CREATED).target("IP_ADDRESS", id).detail("address", ip.canonical())
                .detail("allocation", allocation(r.allocation())).success();
        return find(id);
    }

    @Transactional
    public IpView update(UUID id, IpRequest r) {
        IpView before = find(id);
        jdbc.update("UPDATE ip_addresses SET mac_address = :mac, hostname = :host, allocation = :alloc, notes = :notes, "
                + "updated_at = now() WHERE id = :id", new MapSqlParameterSource("id", id).addValue("mac", mac(r.macAddress()))
                        .addValue("host", blank(r.hostname())).addValue("alloc", allocation(r.allocation()))
                        .addValue("notes", blank(r.notes())));
        audit.event(AuditAction.IP_ADDRESS_UPDATED).target("IP_ADDRESS", id).detail("address", before.address())
                .detail("before", before.allocation() + " " + before.hostname() + " " + before.macAddress())
                .detail("after", allocation(r.allocation()) + " " + blank(r.hostname()) + " " + mac(r.macAddress())).success();
        return find(id);
    }

    @Transactional
    public void delete(UUID id) {
        IpView ip = find(id);
        jdbc.update("DELETE FROM ip_addresses WHERE id = :id", new MapSqlParameterSource("id", id));
        audit.event(AuditAction.IP_ADDRESS_DELETED).target("IP_ADDRESS", id).detail("address", ip.address()).success();
    }

    /**
     * CSV import (for example a DHCP or DNS export): {@code address,mac,hostname,allocation} per line,
     * every column after the address optional; a header line is skipped.
     */
    @Transactional
    public ImportResult importCsv(String csv) {
        if (csv == null || csv.isBlank()) throw ApiException.badRequest("EMPTY_IMPORT", "Nothing to import");
        String[] lines = csv.split("\\R");
        if (lines.length > MAX_IMPORT_LINES) throw ApiException.badRequest("IMPORT_TOO_LARGE", "At most " + MAX_IMPORT_LINES + " lines");
        int imported = 0;
        int updated = 0;
        List<String> errors = new ArrayList<>();
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] col = line.split("[,;\\t]", -1);
            if (n == 0 && !IpAddresses.isValid(col[0].trim())) continue; // header
            try {
                IpAddresses.ParsedIp ip = parseIp(col[0].trim());
                String mac = col.length > 1 ? mac(col[1]) : null;
                String host = col.length > 2 ? blank(col[2]) : null;
                if (host != null && host.length() > 253) throw ApiException.badRequest("INVALID", "Host name too long");
                String alloc = col.length > 3 ? allocation(col[3]) : "UNKNOWN";
                if (upsertManual(ip, mac, host, alloc, null, IpSource.IMPORT)) imported++; else updated++;
            } catch (ApiException e) {
                if (errors.size() < 50) errors.add((n + 1) + ": " + e.getMessage());
            }
        }
        audit.event(AuditAction.IP_ADDRESSES_IMPORTED).detail("imported", imported).detail("updated", updated)
                .detail("errors", errors.size()).success();
        return new ImportResult(imported, updated, errors);
    }
}
