package gov.mot.netmon.event;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import gov.mot.netmon.config.AppProperties;

/**
 * Records timeline events. Recording never fails the caller's operation: problems are logged.
 * Inside a transaction the event is written only after that transaction commits, so the
 * timeline never shows something that was rolled back.
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventWriter writer;
    private final Clock clock;
    private final int maxPerServerPerHour;
    /** Flood protection: events per server (or platform) in the current hour. */
    private final Map<String, int[]> hourly = new ConcurrentHashMap<>();
    private volatile long currentHour;

    public EventService(EventWriter writer, Clock clock, AppProperties props) {
        this.writer = writer;
        this.clock = clock;
        this.maxPerServerPerHour = props.events().maxPerServerPerHour();
    }

    public Builder event(EventCategory category, String type, EventSeverity severity) {
        return new Builder(category, type, severity);
    }

    void store(NewEvent e, boolean immediate) {
        if (!immediate && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    writeSafely(e);
                }
            });
        } else {
            writeSafely(e);
        }
    }

    private void writeSafely(NewEvent e) {
        Instant now = clock.instant();
        try {
            if (!admit(e, now)) return;
            writer.write(e, now);
        } catch (RuntimeException ex) {
            log.error("Could not store event {} for server {}: {}", e.type(), e.serverId(), ex.toString());
        }
    }

    /** Allows at most maxPerServerPerHour events per server per hour; reports the overflow once. */
    private boolean admit(NewEvent e, Instant now) {
        long hour = now.truncatedTo(ChronoUnit.HOURS).getEpochSecond();
        if (hour != currentHour) {
            hourly.clear();
            currentHour = hour;
        }
        String key = e.serverId() == null ? "platform" : e.serverId().toString();
        int[] count = hourly.computeIfAbsent(key, k -> new int[1]);
        int n;
        synchronized (count) {
            n = ++count[0];
        }
        if (n <= maxPerServerPerHour) return true;
        if (n == maxPerServerPerHour + 1) {
            writer.write(new NewEvent(now, EventCategory.SYSTEM, "EVENTS_SUPPRESSED", EventSeverity.WARNING, false,
                    e.serverId(), e.serverName(), null, null,
                    "More than " + maxPerServerPerHour + " events in one hour; further events are dropped until the next hour",
                    Map.of("limit", maxPerServerPerHour), null, null, null), now);
        }
        return false;
    }

    public final class Builder {
        private final EventCategory category;
        private final String type;
        private final EventSeverity severity;
        private Instant at;
        private boolean fromAgent;
        private UUID serverId;
        private String serverName;
        private String resource;
        private String ip;
        private String summary;
        private final Map<String, Object> details = new LinkedHashMap<>();
        private UUID correlationId;
        private UUID alertId;
        private UUID captureId;

        private Builder(EventCategory category, String type, EventSeverity severity) {
            this.category = category;
            this.type = type;
            this.severity = severity;
        }

        public Builder at(Instant t) {
            this.at = t;
            return this;
        }

        public Builder fromAgent() {
            this.fromAgent = true;
            return this;
        }

        public Builder server(UUID id, String name) {
            this.serverId = id;
            this.serverName = name;
            return this;
        }

        public Builder resource(String r) {
            this.resource = r;
            return this;
        }

        public Builder ip(String address) {
            this.ip = address;
            return this;
        }

        public Builder summary(String s) {
            this.summary = s;
            return this;
        }

        public Builder detail(String key, Object value) {
            if (value != null) details.put(key, value);
            return this;
        }

        public Builder correlation(UUID id) {
            this.correlationId = id;
            return this;
        }

        public Builder alert(UUID id) {
            this.alertId = id;
            if (correlationId == null) correlationId = id;
            return this;
        }

        public Builder capture(UUID id) {
            this.captureId = id;
            if (correlationId == null) correlationId = id;
            return this;
        }

        private NewEvent build() {
            return new NewEvent(at == null ? clock.instant() : at, category, type, severity, fromAgent, serverId, serverName,
                    resource, ip, summary == null ? type : summary, Map.copyOf(details), correlationId, alertId, captureId);
        }

        /** Stored after the current transaction commits (or now if there is none). */
        public void record() {
            store(build(), false);
        }

        /** Stored immediately in its own transaction (security events, like audit records). */
        public void recordNow() {
            store(build(), true);
        }
    }
}
