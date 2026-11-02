package gov.mot.netmon.flow;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Allowlist of exporter addresses (registered devices with flow export on). */
@Component
public class FlowExporters {

    public record Exporter(UUID deviceId, String name) {
    }

    private final JdbcTemplate jdbc;
    private volatile Map<String, Exporter> byAddress = Map.of();

    public FlowExporters(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reload() {
        Map<String, Exporter> m = new ConcurrentHashMap<>();
        jdbc.query("SELECT id, name, exporter_ip FROM network_devices WHERE flow_export AND exporter_ip IS NOT NULL",
                rs -> {
                    m.put(rs.getString("exporter_ip"), new Exporter(rs.getObject("id", UUID.class), rs.getString("name")));
                });
        byAddress = m;
    }

    public Exporter find(String address) {
        return byAddress.get(address);
    }
}
