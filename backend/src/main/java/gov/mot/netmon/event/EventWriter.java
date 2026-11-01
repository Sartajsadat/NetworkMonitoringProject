package gov.mot.netmon.event;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Inserts events in their own transaction (like audit records). */
@Component
class EventWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    EventWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(NewEvent e, Instant receivedAt) {
        String details;
        try {
            details = e.details() == null || e.details().isEmpty() ? null : mapper.writeValueAsString(e.details());
        } catch (JsonProcessingException ex) {
            details = null;
        }
        jdbc.update("""
                INSERT INTO events (occurred_at, received_at, category, event_type, severity, source, server_id, server_name,
                    resource, ip_address, summary, details, correlation_id, alert_id, capture_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)""",
                Timestamp.from(e.occurredAt()), Timestamp.from(receivedAt), e.category().name(), e.type(), e.severity().name(),
                e.fromAgent() ? "AGENT" : "PLATFORM", e.serverId(), cut(e.serverName(), 120), cut(e.resource(), 255),
                cut(e.ipAddress(), 45), cut(e.summary(), 500), details, e.correlationId(), e.alertId(), e.captureId());
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
