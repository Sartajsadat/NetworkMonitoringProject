package gov.mot.netmon.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import gov.mot.netmon.agent.AgentProtocol;

class MetricWriterTest {

    @Test
    void extractsOnlyMeasuredValues() {
        var report = new AgentProtocol.Report("0.2.0", Instant.now(), 30,
                new AgentProtocol.Host("h", "LINUX", null, null, null, null, 1L, null, 2),
                new AgentProtocol.Cpu(null, 1.5, null, 0.3, 0.2, 0.1, null),
                new AgentProtocol.Memory(100L, 60L, 40L, 40.0, 0L, 0L, null),
                List.of(new AgentProtocol.Disk("/", "/dev/sda1", "ext4", 100L, 50L, 50L, 50.0)),
                null,
                List.of(new AgentProtocol.NetworkInterface("eth0", "up", null, List.of(), null, null, 1L, 1L, 1L, 1L,
                        0L, 0L, 0L, 0L, Double.NaN, 5.0, null, null, null, null)),
                null, null, null);
        var samples = MetricWriter.extract(report);
        assertThat(samples).extracting(MetricWriter.Sample::metric)
                .contains(MetricNames.CPU_IOWAIT, MetricNames.LOAD_1, MetricNames.MEM_USED_PCT, MetricNames.DISK_USED_PCT,
                        MetricNames.NET_TX_BPS)
                .doesNotContain(MetricNames.CPU_USAGE, MetricNames.SWAP_USED_PCT, MetricNames.NET_RX_BPS);
        assertThat(samples).filteredOn(s -> s.metric().equals(MetricNames.DISK_USED_PCT))
                .extracting(MetricWriter.Sample::resource).containsExactly("/");
    }
}
