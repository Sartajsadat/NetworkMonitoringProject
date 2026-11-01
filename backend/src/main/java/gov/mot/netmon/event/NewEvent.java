package gov.mot.netmon.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** An event ready to be stored. {@code details} must never hold secrets or packet contents. */
public record NewEvent(Instant occurredAt, EventCategory category, String type, EventSeverity severity, boolean fromAgent,
        UUID serverId, String serverName, String resource, String ipAddress, String summary, Map<String, Object> details,
        UUID correlationId, UUID alertId, UUID captureId) {
}
