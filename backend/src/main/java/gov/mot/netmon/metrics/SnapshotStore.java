package gov.mot.netmon.metrics;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import gov.mot.netmon.agent.AgentProtocol.Report;

/** Latest full report per server (JSONB). Older reports (e.g. buffered ones) never overwrite newer ones. */
@Repository
public class SnapshotStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SnapshotStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record Snapshot(UUID serverId, Instant collectedAt, Instant receivedAt, Report report) {
    }

    public void upsertIfNewer(UUID serverId, Report report, Instant receivedAt) {
        jdbc.update("""
                INSERT INTO server_snapshots (server_id, collected_at, received_at, payload) VALUES (?, ?, ?, ?::jsonb)
                ON CONFLICT (server_id) DO UPDATE
                   SET collected_at = EXCLUDED.collected_at, received_at = EXCLUDED.received_at, payload = EXCLUDED.payload
                 WHERE server_snapshots.collected_at < EXCLUDED.collected_at""",
                serverId, Timestamp.from(report.collectedAt()), Timestamp.from(receivedAt), toJson(report));
    }

    public Optional<Snapshot> find(UUID serverId) {
        return jdbc.query("SELECT server_id, collected_at, received_at, payload::text FROM server_snapshots WHERE server_id = ?",
                (rs, i) -> map(rs.getObject(1, UUID.class), rs.getTimestamp(2), rs.getTimestamp(3), rs.getString(4)),
                serverId).stream().findFirst();
    }

    public Map<UUID, Snapshot> findAll() {
        List<Snapshot> all = jdbc.query("SELECT server_id, collected_at, received_at, payload::text FROM server_snapshots",
                (rs, i) -> map(rs.getObject(1, UUID.class), rs.getTimestamp(2), rs.getTimestamp(3), rs.getString(4)));
        return all.stream().collect(java.util.stream.Collectors.toMap(Snapshot::serverId, s -> s));
    }

    public void delete(UUID serverId) {
        jdbc.update("DELETE FROM server_snapshots WHERE server_id = ?", serverId);
    }

    private Snapshot map(UUID id, Timestamp collected, Timestamp received, String json) {
        try {
            return new Snapshot(id, collected.toInstant(), received.toInstant(), mapper.readValue(json, Report.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt snapshot for server " + id, e);
        }
    }

    private String toJson(Report r) {
        try {
            return mapper.writeValueAsString(r);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
