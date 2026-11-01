package gov.mot.netmon.alert;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import gov.mot.netmon.alert.Evaluation.Hit;
import gov.mot.netmon.event.EventService;
import gov.mot.netmon.event.EventSeverity;
import gov.mot.netmon.server.Server;
import gov.mot.netmon.server.ServerRepository;

/**
 * Evaluates all enabled rules on a schedule and keeps alerts in step:
 * opens a new alert (unless the same resource was resolved within the rule's cooldown),
 * updates a still-active one (deduplication), and resolves it when the condition clears.
 */
@Component
public class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);
    static final EnumSet<AlertStatus> ACTIVE = EnumSet.of(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED);

    private final AlertRuleRepository rules;
    private final AlertRepository alerts;
    private final ServerRepository servers;
    private final RuleEvaluator evaluator;
    private final EventService events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AlertEngine(AlertRuleRepository rules, AlertRepository alerts, ServerRepository servers, RuleEvaluator evaluator,
            EventService events, PlatformTransactionManager txm, Clock clock) {
        this.rules = rules;
        this.alerts = alerts;
        this.servers = servers;
        this.evaluator = evaluator;
        this.events = events;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.alerts.evaluation-interval}", initialDelayString = "PT1M")
    public void scheduled() {
        evaluateAll();
    }

    /** Evaluates every enabled rule, each in its own transaction. Public for tests. */
    public synchronized void evaluateAll() {
        Map<UUID, String> names = new HashMap<>();
        for (Server s : servers.findAll()) {
            if (s.acceptsMetrics()) names.put(s.getId(), s.getName());
        }
        for (AlertRule rule : rules.findByEnabledTrue()) {
            try {
                tx.executeWithoutResult(st -> apply(rule, evaluator.evaluate(rule, names)));
            } catch (RuntimeException e) {
                log.error("Alert rule '{}' could not be evaluated: {}", rule.getName(), e.toString());
            }
        }
    }

    void apply(AlertRule rule, Evaluation ev) {
        Instant now = clock.instant();
        Map<String, Alert> active = new HashMap<>();
        for (Alert a : alerts.findByRuleIdAndStatusIn(rule.getId(), ACTIVE)) active.put(a.getDedupKey(), a);

        for (var e : ev.firing.entrySet()) {
            Hit hit = e.getValue();
            Alert existing = active.remove(e.getKey());
            if (existing != null) {
                existing.stillTriggered(hit.value(), hit.condition(), now);
                continue;
            }
            Instant lastResolved = alerts.lastResolvedAt(e.getKey()).orElse(null);
            if (lastResolved != null && lastResolved.plusSeconds(rule.getCooldownSeconds()).isAfter(now)) {
                continue; // within cooldown after the previous alert for this resource
            }
            Alert a = alerts.save(new Alert(rule, e.getKey(), hit.serverId(), hit.serverName(), hit.resource(), hit.condition(),
                    hit.value(), now));
            events.event(rule.getRuleType().category, rule.getRuleType().name(), rule.getSeverity())
                    .server(hit.serverId(), hit.serverName()).resource(hit.resource()).alert(a.getId())
                    .ip(rule.getRuleType() == AlertRuleType.FAILED_LOGINS ? hit.resource() : null)
                    .summary("Alert \"" + rule.getName() + "\": " + hit.condition()).detail("rule", rule.getName())
                    .detail("ruleType", rule.getRuleType().name()).detail("value", hit.value())
                    .detail("threshold", rule.getThreshold()).detail("windowSeconds", rule.getWindowSeconds())
                    .detail("unit", rule.getRuleType().unit.name()).record();
        }
        // Remaining active alerts: resolve where we could check and the condition no longer holds.
        for (Alert a : active.values()) {
            UUID scope = a.getServerId() == null ? Evaluation.PLATFORM : a.getServerId();
            if (!ev.checked.contains(scope)) continue; // no fresh data for this server: leave it open
            Double v = ev.values.get(a.getDedupKey());
            String note = "Condition cleared" + (v == null ? "" : " (now " + RuleEvaluator.fmt(v, rule) + ")");
            a.resolve("AUTO", null, note, now);
            resolvedEvent(a, note, null);
        }
    }

    /** A rule was changed, disabled or deleted: close its alerts; they reopen at once if still true. */
    public void closeForRuleChange(UUID ruleId, String by, String why) {
        Instant now = clock.instant();
        List<Alert> list = alerts.findByRuleIdAndStatusIn(ruleId, ACTIVE);
        for (Alert a : list) {
            a.resolve("RULE_CHANGED", by, why, now);
            resolvedEvent(a, why, by);
        }
    }

    void resolvedEvent(Alert a, String note, String by) {
        events.event(a.getCategory(), "ALERT_RESOLVED", EventSeverity.INFO).server(a.getServerId(), a.getServerName())
                .resource(a.getResource()).alert(a.getId())
                .summary("Alert \"" + a.getRuleName() + "\" resolved" + (by == null ? "" : " by " + by) + ": " + note)
                .detail("rule", a.getRuleName()).detail("by", by).detail("resolution", note).record();
    }
}
