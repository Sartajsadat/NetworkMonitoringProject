package gov.mot.netmon.server;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.audit.AuditAction;
import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.common.IpAddresses;
import gov.mot.netmon.common.PageRequests;
import gov.mot.netmon.common.PageResponse;
import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.security.CurrentUser;
import gov.mot.netmon.server.ServerDtos.ServerRequest;
import gov.mot.netmon.server.ServerDtos.ServerResponse;
import gov.mot.netmon.server.ServerEnums.Environment;
import gov.mot.netmon.server.ServerEnums.MonitoringStatus;
import gov.mot.netmon.server.ServerEnums.OsFamily;

@Service
public class ServerService {

    private static final Set<String> SORT_FIELDS =
            Set.of("name", "hostname", "environment", "organization", "osFamily", "lastSeenAt", "createdAt");
    private static final Pattern HOSTNAME = Pattern.compile(
            "^(?=.{1,253}$)[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\\.?$");

    private final ServerRepository servers;
    private final ServerGroupRepository groups;
    private final AuditService audit;
    private final Clock clock;
    private final Duration onlineThreshold;

    public ServerService(ServerRepository servers, ServerGroupRepository groups, AuditService audit, Clock clock,
            AppProperties props) {
        this.servers = servers;
        this.groups = groups;
        this.audit = audit;
        this.clock = clock;
        this.onlineThreshold = props.monitoring().onlineThreshold();
    }

    public record Filter(String search, Environment environment, OsFamily osFamily, String organization,
            MonitoringStatus monitoringStatus, UUID groupId) {
    }

    @Transactional(readOnly = true)
    public PageResponse<ServerResponse> search(Filter f, int page, int size, String sort) {
        Specification<Server> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f.search() != null && !f.search().isBlank()) {
                String like = "%" + f.search().trim().toLowerCase(Locale.ROOT) + "%";
                var sub = q.subquery(UUID.class);
                var ip = sub.from(ServerIpAddress.class);
                sub.select(ip.get("server").get("id")).where(cb.like(ip.get("ipAddress"), like));
                p.add(cb.or(cb.like(cb.lower(root.get("name")), like), cb.like(cb.lower(root.get("hostname")), like),
                        cb.like(cb.lower(root.get("organization")), like), root.get("id").in(sub)));
            }
            if (f.environment() != null) p.add(cb.equal(root.get("environment"), f.environment()));
            if (f.osFamily() != null) p.add(cb.equal(root.get("osFamily"), f.osFamily()));
            if (f.organization() != null && !f.organization().isBlank()) {
                p.add(cb.equal(root.get("organization"), f.organization()));
            }
            if (f.monitoringStatus() != null) p.add(cb.equal(root.get("monitoringStatus"), f.monitoringStatus()));
            if (f.groupId() != null) {
                Join<Server, ServerGroup> g = root.join("groups", JoinType.INNER);
                p.add(cb.equal(g.get("id"), f.groupId()));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        var pageable = PageRequests.of(page, size, sort, SORT_FIELDS, Sort.by("name"));
        return PageResponse.of(servers.findAll(spec, pageable), this::toResponse);
    }

    @Transactional(readOnly = true)
    public ServerResponse get(UUID id) {
        return toResponse(find(id));
    }

    @Transactional(readOnly = true)
    public List<String> organizations() {
        return servers.findDistinctOrganizations();
    }

    @Transactional
    public ServerResponse create(ServerRequest req) {
        if (servers.existsByNameIgnoreCase(req.name().trim())) {
            throw ApiException.conflict("SERVER_NAME_TAKEN", "A server with this name already exists");
        }
        Server server = new Server(CurrentUser.usernameOrNull());
        apply(server, req, MonitoringStatus.PENDING_AGENT);
        servers.save(server);
        audit.event(AuditAction.SERVER_REGISTERED).target("SERVER", server.getId())
                .detail("name", server.getName()).detail("hostname", server.getHostname())
                .detail("environment", server.getEnvironment()).success();
        return toResponse(server);
    }

    @Transactional
    public ServerResponse update(UUID id, ServerRequest req) {
        Server server = find(id);
        if (servers.existsByNameIgnoreCaseAndIdNot(req.name().trim(), id)) {
            throw ApiException.conflict("SERVER_NAME_TAKEN", "A server with this name already exists");
        }
        ServerResponse before = toResponse(server);
        apply(server, req, server.getMonitoringStatus());
        servers.flush();
        ServerResponse after = toResponse(server);
        audit.event(AuditAction.SERVER_UPDATED).target("SERVER", id).detail("name", server.getName())
                .detail("changes", diff(before, after)).success();
        return after;
    }

    @Transactional
    public void delete(UUID id) {
        Server server = find(id);
        servers.delete(server);
        audit.event(AuditAction.SERVER_DELETED).target("SERVER", id).detail("name", server.getName())
                .detail("hostname", server.getHostname()).success();
    }

    private void apply(Server server, ServerRequest req, MonitoringStatus defaultStatus) {
        String hostname = req.hostname().trim();
        if (!HOSTNAME.matcher(hostname).matches() && !IpAddresses.isValid(hostname)) {
            throw ApiException.badRequest("INVALID_HOSTNAME", "Hostname must be a valid DNS name or IP address");
        }
        MonitoringStatus status = req.monitoringStatus() == null ? defaultStatus : req.monitoringStatus();
        if (status == MonitoringStatus.ACTIVE && server.getMonitoringStatus() != MonitoringStatus.ACTIVE) {
            throw ApiException.badRequest("INVALID_STATUS",
                    "ACTIVE is set automatically when an agent starts reporting; choose PENDING_AGENT, PAUSED or "
                            + "DECOMMISSIONED");
        }
        server.applyDetails(req.name().trim(), hostname, req.osFamily(), trim(req.osVersion()), req.environment(),
                req.organization().trim(), trim(req.purpose()), trim(req.location()), status, trim(req.notes()),
                CurrentUser.usernameOrNull());
        server.replaceIpAddresses(req.ipAddresses() == null ? List.of()
                : req.ipAddresses().stream().map(i -> new Server.IpEntry(i.address(), i.label())).toList());
        Set<UUID> groupIds = req.groupIds() == null ? Set.of() : req.groupIds();
        List<ServerGroup> found = groups.findAllById(groupIds);
        if (found.size() != groupIds.size()) {
            throw ApiException.badRequest("UNKNOWN_GROUP", "One or more server groups do not exist");
        }
        server.replaceGroups(new HashSet<>(found));
    }

    private ServerResponse toResponse(Server s) {
        return ServerResponse.from(s, s.connectionStatus(clock.instant(), onlineThreshold));
    }

    private Server find(UUID id) {
        return servers.findById(id).orElseThrow(() -> ApiException.notFound("Server"));
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Field-level before/after map for the audit trail. */
    private static java.util.Map<String, Object> diff(ServerResponse a, ServerResponse b) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        cmp(out, "name", a.name(), b.name());
        cmp(out, "hostname", a.hostname(), b.hostname());
        cmp(out, "osFamily", a.osFamily(), b.osFamily());
        cmp(out, "osVersion", a.osVersion(), b.osVersion());
        cmp(out, "environment", a.environment(), b.environment());
        cmp(out, "organization", a.organization(), b.organization());
        cmp(out, "purpose", a.purpose(), b.purpose());
        cmp(out, "location", a.location(), b.location());
        cmp(out, "monitoringStatus", a.monitoringStatus(), b.monitoringStatus());
        cmp(out, "ipAddresses", a.ipAddresses().stream().map(ServerDtos.IpAddressDto::address).toList(),
                b.ipAddresses().stream().map(ServerDtos.IpAddressDto::address).toList());
        cmp(out, "groups", a.groups().stream().map(ServerDtos.GroupRef::name).toList(),
                b.groups().stream().map(ServerDtos.GroupRef::name).toList());
        return out;
    }

    private static void cmp(java.util.Map<String, Object> out, String field, Object before, Object after) {
        if (!java.util.Objects.equals(before, after)) {
            java.util.Map<String, Object> change = new java.util.LinkedHashMap<>();
            change.put("before", before == null ? null : before.toString());
            change.put("after", after == null ? null : after.toString());
            out.put(field, change);
        }
    }
}
