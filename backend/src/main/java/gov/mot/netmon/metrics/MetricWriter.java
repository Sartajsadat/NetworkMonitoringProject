package gov.mot.netmon.metrics;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import gov.mot.netmon.agent.AgentProtocol.Report;

/** Turns an agent report into history rows. Missing (null) values are skipped, never stored as zero. */
@Component
public class MetricWriter {

    private static final String INSERT = "INSERT INTO metric_samples (server_id, metric, resource, collected_at, value) "
            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";

    private final JdbcTemplate jdbc;

    public MetricWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Sample(String metric, String resource, double value) {
    }

    public int write(UUID serverId, Report r) {
        List<Sample> samples = extract(r);
        if (samples.isEmpty()) {
            return 0;
        }
        Timestamp at = Timestamp.from(r.collectedAt());
        jdbc.batchUpdate(INSERT, samples, 200, (ps, s) -> {
            ps.setObject(1, serverId);
            ps.setString(2, s.metric());
            ps.setString(3, s.resource());
            ps.setTimestamp(4, at);
            ps.setDouble(5, s.value());
        });
        return samples.size();
    }

    static List<Sample> extract(Report r) {
        List<Sample> out = new ArrayList<>();
        if (r.cpu() != null) {
            add(out, MetricNames.CPU_USAGE, "", r.cpu().usagePercent());
            add(out, MetricNames.CPU_IOWAIT, "", r.cpu().iowaitPercent());
            add(out, MetricNames.LOAD_1, "", r.cpu().load1());
            add(out, MetricNames.LOAD_5, "", r.cpu().load5());
            add(out, MetricNames.LOAD_15, "", r.cpu().load15());
        }
        if (r.memory() != null) {
            add(out, MetricNames.MEM_USED_PCT, "", r.memory().usedPercent());
            add(out, MetricNames.MEM_USED_BYTES, "", r.memory().usedBytes() == null ? null : r.memory().usedBytes().doubleValue());
            add(out, MetricNames.SWAP_USED_PCT, "", r.memory().swapUsedPercent());
        }
        if (r.disks() != null) {
            r.disks().forEach(d -> add(out, MetricNames.DISK_USED_PCT, d.mount(), d.usedPercent()));
        }
        if (r.diskIo() != null) {
            r.diskIo().forEach(d -> {
                add(out, MetricNames.DISKIO_READ_BPS, d.device(), d.readBytesPerSec());
                add(out, MetricNames.DISKIO_WRITE_BPS, d.device(), d.writeBytesPerSec());
                add(out, MetricNames.DISKIO_BUSY_PCT, d.device(), d.busyPercent());
            });
        }
        if (r.network() != null) {
            r.network().forEach(n -> {
                add(out, MetricNames.NET_RX_BPS, n.name(), n.rxBitsPerSec());
                add(out, MetricNames.NET_TX_BPS, n.name(), n.txBitsPerSec());
                add(out, MetricNames.NET_RX_PPS, n.name(), n.rxPacketsPerSec());
                add(out, MetricNames.NET_TX_PPS, n.name(), n.txPacketsPerSec());
                add(out, MetricNames.NET_ERRORS_PS, n.name(), n.errorsPerSec());
                add(out, MetricNames.NET_DROPS_PS, n.name(), n.dropsPerSec());
            });
        }
        return out;
    }

    private static void add(List<Sample> out, String metric, String resource, Double value) {
        if (value != null && !value.isNaN() && !value.isInfinite()) {
            out.add(new Sample(metric, resource == null ? "" : resource, value));
        }
    }
}
