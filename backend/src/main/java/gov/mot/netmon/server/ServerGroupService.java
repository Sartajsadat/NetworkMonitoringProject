package gov.mot.netmon.server;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.audit.AuditAction;
import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.server.ServerDtos.GroupRequest;
import gov.mot.netmon.server.ServerDtos.GroupResponse;

@Service
public class ServerGroupService {

    private final ServerGroupRepository groups;
    private final ServerRepository servers;
    private final AuditService audit;

    public ServerGroupService(ServerGroupRepository groups, ServerRepository servers, AuditService audit) {
        this.groups = groups;
        this.servers = servers;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<GroupResponse> list() {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : groups.countMembersPerGroup()) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return groups.findAll().stream()
                .sorted(Comparator.comparing((ServerGroup g) -> g.getGroupType().name()).thenComparing(ServerGroup::getName))
                .map(g -> toResponse(g, counts.getOrDefault(g.getId(), 0L)))
                .toList();
    }

    @Transactional
    public GroupResponse create(GroupRequest req) {
        String name = req.name().trim();
        if (groups.existsByGroupTypeAndNameIgnoreCase(req.groupType(), name)) {
            throw ApiException.conflict("GROUP_EXISTS", "A group with this type and name already exists");
        }
        ServerGroup g = groups.save(new ServerGroup(name, req.groupType(), blankToNull(req.description())));
        audit.event(AuditAction.SERVER_GROUP_CREATED).target("SERVER_GROUP", g.getId()).detail("name", name)
                .detail("type", g.getGroupType()).success();
        return toResponse(g, 0);
    }

    @Transactional
    public GroupResponse update(UUID id, GroupRequest req) {
        ServerGroup g = groups.findById(id).orElseThrow(() -> ApiException.notFound("Server group"));
        String name = req.name().trim();
        if (groups.existsByGroupTypeAndNameIgnoreCaseAndIdNot(req.groupType(), name, id)) {
            throw ApiException.conflict("GROUP_EXISTS", "A group with this type and name already exists");
        }
        String oldName = g.getName();
        g.update(name, req.groupType(), blankToNull(req.description()));
        audit.event(AuditAction.SERVER_GROUP_UPDATED).target("SERVER_GROUP", id).detail("nameBefore", oldName)
                .detail("nameAfter", name).success();
        return toResponse(g, servers.countByGroupId(id));
    }

    @Transactional
    public void delete(UUID id) {
        ServerGroup g = groups.findById(id).orElseThrow(() -> ApiException.notFound("Server group"));
        groups.delete(g); // membership rows cascade in the database; servers are untouched
        audit.event(AuditAction.SERVER_GROUP_DELETED).target("SERVER_GROUP", id).detail("name", g.getName())
                .success();
    }

    private static GroupResponse toResponse(ServerGroup g, long members) {
        return new GroupResponse(g.getId(), g.getName(), g.getGroupType(), g.getDescription(), members,
                g.getCreatedAt(), g.getUpdatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
