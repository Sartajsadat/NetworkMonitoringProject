package gov.mot.netmon.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real export packets recorded from softflowd 1.1.0 replaying a 25-second capture (HTTP, DNS and ICMP
 * on the loopback interface, 1 Nov 2026 16:51:01-16:51:26 UTC). The same traffic in NetFlow v5, v9
 * and IPFIX must decode to the same flows.
 */
class SoftflowdFixturesTest {

    static final Instant FIRST_PACKET = Instant.parse("2026-11-01T16:51:01Z");
    static final Instant LAST_PACKET = Instant.parse("2026-11-01T16:51:27Z");

    static List<FlowRecord> decode(String version) throws Exception {
        NetflowParser parser = new NetflowParser();
        List<FlowRecord> out = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            try (InputStream in = SoftflowdFixturesTest.class.getResourceAsStream(
                    String.format("/flow/softflowd/%s-%03d.bin", version, i))) {
                byte[] d = in.readAllBytes();
                NetflowParser.Result r = parser.parse("127.0.0.1", d, d.length, Instant.now());
                assertThat(r.skippedSets()).as("template must precede data").isZero();
                out.addAll(r.flows());
            }
        }
        return out;
    }

    @ParameterizedTest
    @ValueSource(strings = {"v5", "v9", "v10"})
    void decodesRealExporterOutput(String version) throws Exception {
        List<FlowRecord> flows = decode(version);
        assertThat(flows).hasSize(226);
        assertThat(flows).allSatisfy(f -> {
            assertThat(f.src().getHostAddress()).isEqualTo("127.0.0.1");
            assertThat(f.start()).isBetween(FIRST_PACKET.minusSeconds(1), LAST_PACKET);
            assertThat(f.end()).isBetween(f.start(), LAST_PACKET);
            assertThat(f.protocol()).isIn(1, 6, 17);
        });
        long bytes = flows.stream().mapToLong(FlowRecord::bytes).sum();
        long packets = flows.stream().mapToLong(FlowRecord::packets).sum();
        List<FlowRecord> v5 = version.equals("v5") ? flows : decode("v5");
        assertThat(bytes).isEqualTo(v5.stream().mapToLong(FlowRecord::bytes).sum());
        assertThat(packets).isEqualTo(v5.stream().mapToLong(FlowRecord::packets).sum());
        assertThat(flows.stream().filter(f -> Integer.valueOf(18081).equals(f.dstPort()))).isNotEmpty();
    }
}
