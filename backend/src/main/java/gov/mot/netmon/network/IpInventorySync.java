package gov.mot.netmon.network;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import gov.mot.netmon.network.IpObservations.Observation;

/**
 * Copies addresses the platform already knows into the inventory: IPs registered on servers and
 * device management addresses. These say the address belongs to something, not that it was seen.
 */
@Component
public class IpInventorySync {

    private final JdbcTemplate jdbc;
    private final IpObservations observations;

    public IpInventorySync(JdbcTemplate jdbc, IpObservations observations) {
        this.jdbc = jdbc;
        this.observations = observations;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStart() {
        sync();
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    public void sync() {
        List<Observation> list = new ArrayList<>();
        jdbc.query("SELECT a.ip_address, s.id, s.hostname FROM server_ip_addresses a JOIN servers s ON s.id = a.server_id",
                rs -> {
                    list.add(new Observation(rs.getString(1), IpSource.SERVER_REGISTRY, rs.getObject(2, java.util.UUID.class), null,
                            null, rs.getString(3), null));
                });
        jdbc.query("SELECT management_ip, id, name FROM network_devices WHERE management_ip IS NOT NULL", rs -> {
            list.add(new Observation(rs.getString(1), IpSource.DEVICE, null, rs.getObject(2, java.util.UUID.class), null,
                    rs.getString(3), null));
        });
        observations.observeAll(list);
    }
}
