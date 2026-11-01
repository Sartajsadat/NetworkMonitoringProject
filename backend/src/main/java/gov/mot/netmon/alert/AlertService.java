package gov.mot.netmon.alert;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.alert.AlertDtos.AlertCounts;
import gov.mot.netmon.alert.AlertDtos.AlertDetail;
import gov.mot.netmon.alert.AlertDtos.AlertView;
import gov.mot.netmon.audit.AuditAction;
import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.common.PageResponse;
import gov.mot.netmon.event.EventAccess;
import gov.mot.netmon.event.EventCategory;
import gov.mot.netmon.event.EventDtos.EventFilter;
import gov.mot.netmon.event.EventDtos.EventView;
import gov.mot.netmon.event.EventQueryService;
import gov.mot.netmon.event.EventService;
import gov.mot.netmon.event.EventSeverity;
import gov.mot.netmon.event.TelemetryCoverage;
import gov.mot.netmon.security.CurrentUser;

@Service
public class AlertService {

    static final Duration AROUND = Duration.ofMinutes(30);

    private final AlertRepository alerts;
    private final AlertRuleService rules;
    private final EventQueryService eventQueries;
    private final EventService events;
    private final TelemetryCoverage coverage;
    private final AuditService audit;
    private final Clock clock;

    public AlertService(AlertRepository alerts, AlertRuleService rules, EventQueryService eventQueries, EventService events,
            TelemetryCoverage coverage, AuditService audit, Clock clock) {
        this.alerts = alerts;
        this.rules = rules;
        this.eventQueries = eventQueries;
        this.events = events;
        this.coverage = coverage;
        this.audit = audit;
        this.clock = clock;
    }

    private static Specification<Alert> visible() {
        Set<EventCategory> hidden = EventAccess.hiddenCategories();
        return (root, q, cb) -> hidden.isEmpty() ? cb.conjunction() : cb.not(root.get("category").in(hidden));
    }

    @Transactional(readOnly = true)
    public PageResponse<AlertView> list(String status, EventSeverity severity, UUID serverId, UUID ruleId, int page, int size) {
        if (page < 0 || size < 1 || size > 200) throw ApiException.badRequest("INVALID_PAGE", "Invalid page");
        Specification<Alert> spec = visible();
        String s = status == null ? "ACTIVE" : status;
        switch (s) {
            case "ACTIVE" -> spec = spec.and((r, q, cb) -> r.get("status").in(AlertEngine.ACTIVE));
            case "OPEN", "ACKNOWLEDGED", "RESOLVED" -> spec = spec.and((r, q, cb) -> cb.equal(r.get("status"), AlertStatus.valueOf(s)));
            case "ALL" -> {
            }
            default -> throw ApiException.badRequest("INVALID_FILTER", "Unknown status");
        }
        if (severity != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("severity"), severity));
        if (serverId != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("serverId"), serverId));
        if (ruleId != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("ruleId"), ruleId));
        Sort sort = "RESOLVED".equals(s) || "ALL".equals(s) ? Sort.by(Sort.Direction.DESC, "lastTriggeredAt")
                : Sort.by(Sort.Direction.DESC, "firstTriggeredAt");
        return PageResponse.of(alerts.findAll(spec, PageRequest.of(page, size, sort)), AlertView::of);
    }

    @Transactional(readOnly = true)
    public AlertCounts counts() {
        List<Alert> active = alerts.findAll(visible().and((r, q, cb) -> r.get("status").in(AlertEngine.ACTIVE)));
        long[] sev = new long[4];
        long open = 0;
        long ack = 0;
        for (Alert a : active) {
            sev[a.getSeverity().ordinal()]++;
            if (a.getStatus() == AlertStatus.OPEN) open++; else ack++;
        }
        return new AlertCounts(open, ack, sev[3], sev[2], sev[1], sev[0]);
    }

    private Alert find(UUID id) {
        Alert a = alerts.findById(id).orElseThrow(() -> ApiException.notFound("Alert"));
        if (EventAccess.hiddenCategories().contains(a.getCategory())) throw ApiException.notFound("Alert");
        return a;
    }

    @Transactional(readOnly = true)
    public AlertDetail get(UUID id) {
        Alert a = find(id);
        Instant now = clock.instant();
        Instant from = a.getFirstTriggeredAt().minus(AROUND);
        Instant end = a.getResolvedAt() != null ? a.getResolvedAt().plus(AROUND) : now;
        Instant to = end.isAfter(now) ? now : end;
        // the alert's own events, plus everything on the same server around it
        List<EventView> list = new ArrayList<>(eventQueries.search(new EventFilter(from.minus(Duration.ofDays(1)), now, null, null,
                null, null, null, null, null, a.getId(), null), 0, 200, true).content());
        if (a.getServerId() != null) {
            for (EventView e : eventQueries.search(new EventFilter(from, to, a.getServerId(), null, null, null, null, null, null,
                    null, null), 0, 200, true).content()) {
                if (list.stream().noneMatch(x -> x.id() == e.id())) list.add(e);
            }
            list.sort((x, y) -> x.occurredAt().compareTo(y.occurredAt()));
        }
        return new AlertDetail(AlertView.of(a), a.getRuleId() == null ? null : rules.viewOrNull(a.getRuleId()), list, from, to,
                a.getServerId() == null ? null : coverage.coverage(a.getServerId(), from, to));
    }

    @Transactional
    public AlertView acknowledge(UUID id, String comment) {
        Alert a = find(id);
        if (a.getStatus() != AlertStatus.OPEN) throw ApiException.conflict("ALERT_NOT_OPEN", "Only open alerts can be acknowledged");
        String user = CurrentUser.require().username();
        a.acknowledge(user, blankToNull(comment), clock.instant());
        audit.event(AuditAction.ALERT_ACKNOWLEDGED).target("ALERT", id).detail("rule", a.getRuleName())
                .detail("server", a.getServerName()).reason(blankToNull(comment)).success();
        events.event(a.getCategory(), "ALERT_ACKNOWLEDGED", EventSeverity.INFO).server(a.getServerId(), a.getServerName())
                .resource(a.getResource()).alert(a.getId())
                .summary("Alert \"" + a.getRuleName() + "\" acknowledged by " + user + (comment == null || comment.isBlank() ? "" : ": " + comment.trim()))
                .detail("rule", a.getRuleName()).detail("by", user).detail("comment", blankToNull(comment)).record();
        return AlertView.of(a);
    }

    @Transactional
    public AlertView resolve(UUID id, String comment) {
        Alert a = find(id);
        if (!a.isActive()) throw ApiException.conflict("ALERT_RESOLVED", "This alert is already resolved");
        String user = CurrentUser.require().username();
        a.resolve("MANUAL", user, comment.trim(), clock.instant());
        audit.event(AuditAction.ALERT_RESOLVED).target("ALERT", id).detail("rule", a.getRuleName())
                .detail("server", a.getServerName()).reason(comment.trim()).success();
        events.event(a.getCategory(), "ALERT_RESOLVED", EventSeverity.INFO).server(a.getServerId(), a.getServerName())
                .resource(a.getResource()).alert(a.getId())
                .summary("Alert \"" + a.getRuleName() + "\" resolved by " + user + ": " + comment.trim())
                .detail("rule", a.getRuleName()).detail("by", user).detail("resolution", comment.trim()).record();
        return AlertView.of(a);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
