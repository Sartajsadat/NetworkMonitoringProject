package gov.mot.netmon.flow;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import gov.mot.netmon.common.IpAddresses;
import gov.mot.netmon.network.IpObservations;
import gov.mot.netmon.network.IpSource;

/**
 * Writes queued flows in batches every second, updates each exporter's counters, and records
 * addresses that fall inside registered subnets in the IP inventory (internet addresses are not
 * added to the inventory).
 */
@Component
public class FlowWriter {

    private static final Logger log = LoggerFactory.getLogger(FlowWriter.class);
    static final int BATCH = 5000;

    private final FlowCollector collector;
    private final JdbcTemplate jdbc;
    private final IpObservations observations;
    private final SubnetIndex subnets;

    public FlowWriter(FlowCollector collector, JdbcTemplate jdbc, IpObservations observations, SubnetIndex subnets) {
        this.collector = collector;
        this.jdbc = jdbc;
        this.observations = observations;
        this.subnets = subnets;
    }

    @Scheduled(fixedDelayString = "PT1S")
    public void flush() {
        try {
            while (drainOnce() == BATCH) {
                // keep going while the queue is full of work
            }
            updateDropped();
        } catch (RuntimeException e) {
            log.error("Writing flows failed: {}", e.toString());
        }
    }

    /** Package-visible for tests. Returns how many flows were written. */
    int drainOnce() {
        List<FlowCollector.Received> batch = new ArrayList<>(BATCH);
        collector.queue.drainTo(batch, BATCH);
        if (batch.isEmpty()) return 0;
        jdbc.batchUpdate("""
                INSERT INTO network_flows (exporter_id, received_at, flow_start, flow_end, src_ip, dst_ip, src_port, dst_port,
                    protocol, bytes, packets, tcp_flags, input_if, output_if, sampling, version)
                VALUES (?, ?, ?, ?, ?::inet, ?::inet, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", batch, batch.size(), (ps, r) -> {
            FlowRecord f = r.flow();
            ps.setObject(1, r.exporter().deviceId());
            ps.setTimestamp(2, Timestamp.from(r.receivedAt()));
            ps.setTimestamp(3, Timestamp.from(f.start()));
            ps.setTimestamp(4, Timestamp.from(f.end()));
            ps.setString(5, IpAddresses.parse(f.src().getHostAddress()).canonical());
            ps.setString(6, IpAddresses.parse(f.dst().getHostAddress()).canonical());
            ps.setObject(7, f.srcPort());
            ps.setObject(8, f.dstPort());
            ps.setInt(9, f.protocol());
            ps.setLong(10, f.bytes());
            ps.setLong(11, f.packets());
            ps.setObject(12, f.tcpFlags());
            ps.setObject(13, f.inputIf());
            ps.setObject(14, f.outputIf());
            ps.setInt(15, f.sampling());
            ps.setInt(16, f.version());
        });
        Map<UUID, Object[]> perExporter = new HashMap<>(); // count, last end
        for (FlowCollector.Received r : batch) {
            Object[] s = perExporter.computeIfAbsent(r.exporter().deviceId(), k -> new Object[] {0L, r.receivedAt()});
            s[0] = (Long) s[0] + 1;
            for (var a : new java.net.InetAddress[] {r.flow().src(), r.flow().dst()}) {
                String ip = IpAddresses.parse(a.getHostAddress()).canonical();
                if (subnets.inRegisteredSubnet(ip)) {
                    observations.observe(new IpObservations.Observation(ip, IpSource.FLOW, null, null, null, null, r.flow().end()));
                }
            }
        }
        for (var e : perExporter.entrySet()) {
            jdbc.update("UPDATE network_devices SET flows_received = flows_received + ?, last_flow_at = ?, last_flow_version = ?, "
                    + "sampling_interval = ? WHERE id = ?", e.getValue()[0], Timestamp.from((java.time.Instant) e.getValue()[1]),
                    collector.versions.get(e.getKey()), collector.sampling.get(e.getKey()), e.getKey());
        }
        return batch.size();
    }

    private void updateDropped() {
        for (var e : collector.dropped.entrySet()) {
            long n = e.getValue().getAndSet(0);
            if (n > 0) jdbc.update("UPDATE network_devices SET flows_dropped = flows_dropped + ? WHERE id = ?", n, e.getKey());
        }
    }
}
