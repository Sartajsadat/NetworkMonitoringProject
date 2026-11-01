package gov.mot.netmon.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.common.PageRequests;
import gov.mot.netmon.common.PageResponse;

@Service
public class AuditQueryService {

    private static final Set<String> SORT_FIELDS = Set.of("occurredAt", "action", "actorUsername", "result");

    private final AuditLogRepository repository;

    public AuditQueryService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public record Filter(Instant from, Instant to, String actor, String action, AuditResult result,
            String targetType, String targetId) {
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(Filter f, int page, int size, String sort) {
        if (f.from() != null && f.to() != null && f.from().isAfter(f.to())) {
            throw ApiException.badRequest("INVALID_RANGE", "'from' must be before 'to'");
        }
        Specification<AuditLog> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f.from() != null) p.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), f.from()));
            if (f.to() != null) p.add(cb.lessThan(root.get("occurredAt"), f.to()));
            if (notBlank(f.actor())) p.add(cb.equal(cb.lower(root.get("actorUsername")), f.actor().trim().toLowerCase()));
            if (notBlank(f.action())) p.add(cb.equal(root.get("action"), f.action().trim()));
            if (f.result() != null) p.add(cb.equal(root.get("result"), f.result()));
            if (notBlank(f.targetType())) p.add(cb.equal(root.get("targetType"), f.targetType().trim()));
            if (notBlank(f.targetId())) p.add(cb.equal(root.get("targetId"), f.targetId().trim()));
            return cb.and(p.toArray(Predicate[]::new));
        };
        var pageable = PageRequests.of(page, size, sort, SORT_FIELDS, Sort.by(Sort.Direction.DESC, "occurredAt"));
        return PageResponse.of(repository.findAll(spec, pageable), AuditLogResponse::from);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
