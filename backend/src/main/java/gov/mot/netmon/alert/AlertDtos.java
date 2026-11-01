package gov.mot.netmon.alert;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import gov.mot.netmon.event.EventCategory;
import gov.mot.netmon.event.EventDtos.Coverage;
import gov.mot.netmon.event.EventDtos.EventView;
import gov.mot.netmon.event.EventSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AlertDtos {

    private AlertDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AlertView(UUID id, UUID ruleId, String ruleName, AlertRuleType ruleType, String unit, EventCategory category,
            EventSeverity severity, UUID serverId, String serverName, String resource, AlertStatus status, String condition,
            Double threshold, int windowSeconds, Instant firstTriggeredAt, Instant lastTriggeredAt, Double lastValue,
            Double peakValue, int evaluations, Instant acknowledgedAt, String acknowledgedBy, String ackComment,
            Instant resolvedAt, String resolvedBy, String resolutionType, String resolution) {

        static AlertView of(Alert a) {
            return new AlertView(a.getId(), a.getRuleId(), a.getRuleName(), a.getRuleType(), a.getRuleType().unit.name(),
                    a.getCategory(), a.getSeverity(), a.getServerId(), a.getServerName(), a.getResource(), a.getStatus(),
                    a.getConditionText(), a.getThreshold(), a.getWindowSeconds(), a.getFirstTriggeredAt(),
                    a.getLastTriggeredAt(), a.getLastValue(), a.getPeakValue(), a.getEvaluations(), a.getAcknowledgedAt(),
                    a.getAcknowledgedBy(), a.getAckComment(), a.getResolvedAt(), a.getResolvedBy(), a.getResolutionType(),
                    a.getResolution());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AlertDetail(AlertView alert, RuleView rule, List<EventView> events, Instant windowFrom, Instant windowTo,
            Coverage coverage) {
    }

    public record AlertCounts(long open, long acknowledged, long critical, long error, long warning, long info) {
    }

    public record AckRequest(@Size(max = 1000) String comment) {
    }

    public record ResolveRequest(@NotBlank @Size(min = 3, max = 1000) String comment) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuleView(UUID id, String name, String description, AlertRuleType ruleType, String unit,
            EventCategory category, EventSeverity severity, Double threshold, int windowSeconds, int cooldownSeconds,
            UUID serverId, String serverName, String resourceFilter, boolean enabled, boolean builtin, Instant updatedAt,
            String updatedBy, long activeAlerts) {
    }

    public record RuleRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotNull AlertRuleType ruleType,
            @NotNull EventSeverity severity,
            Double threshold,
            @NotNull Integer windowSeconds,
            @NotNull Integer cooldownSeconds,
            UUID serverId,
            @Size(max = 255) String resourceFilter,
            boolean enabled) {
    }

    /** What the UI needs to build the rule form for one type. */
    public record RuleTypeInfo(AlertRuleType type, EventCategory category, String unit, boolean needsThreshold,
            double minThreshold, double maxThreshold, boolean usesWindow, String resourceKind, boolean serverScoped) {
    }
}
