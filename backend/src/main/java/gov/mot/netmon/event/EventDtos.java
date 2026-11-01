package gov.mot.netmon.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

public final class EventDtos {

    private EventDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventView(long id, Instant occurredAt, Instant receivedAt, EventCategory category, String type,
            EventSeverity severity, String source, UUID serverId, String serverName, String resource, String ipAddress,
            String summary, JsonNode details, UUID correlationId, UUID alertId, String alertStatus, UUID captureId) {
    }

    /** Search filters (all optional except the time range, which defaults to the last 24 hours). */
    public record EventFilter(Instant from, Instant to, UUID serverId, String ip, List<EventCategory> categories,
            EventSeverity minSeverity, String service, String alertStatus, String text, UUID correlationId, String type) {
    }

    public record Bucket(Instant t, long info, long warning, long error, long critical) {
    }

    public record Histogram(Instant from, Instant to, long bucketSeconds, List<Bucket> buckets,
            Map<String, Long> bySeverity, Map<String, Long> byCategory, long total) {
    }

    public record Gap(Instant from, Instant to) {
    }

    /**
     * What metric data exists for a server in a window. {@code samples == 0} means nothing was
     * collected: the UI must say so instead of drawing an empty chart as if all was well.
     */
    public record Coverage(Instant from, Instant to, int expectedIntervalSeconds, long samples, long expectedSamples,
            double percent, List<Gap> gaps, String resolution) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CaptureRef(UUID id, String name, String status, Instant startedAt, Instant endedAt, boolean filesAvailable) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AlertRef(UUID id, String ruleName, String status, String severity) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventContext(EventView event, List<EventView> related, Instant windowFrom, Instant windowTo,
            Coverage coverage, List<CaptureRef> captures, boolean capturesHidden, AlertRef alert) {
    }
}
