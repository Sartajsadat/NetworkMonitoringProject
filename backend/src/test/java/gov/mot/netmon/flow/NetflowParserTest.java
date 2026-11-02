package gov.mot.netmon.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class NetflowParserTest {

    final NetflowParser parser = new NetflowParser();
    final Instant now = Instant.parse("2026-11-02T10:00:00Z");

    @Test
    void decodesNetflowV5WithTimesFromUptime() throws Exception {
        byte[] p = Packets.v5(now.getEpochSecond(), "10.1.1.5", 51515, "10.2.2.9", 443, 9000, 12, 0x4000 | 10);
        NetflowParser.Result r = parser.parse("192.0.2.1", p, p.length, now);
        assertThat(r.version()).isEqualTo(5);
        FlowRecord f = r.flows().get(0);
        assertThat(f.src().getHostAddress()).isEqualTo("10.1.1.5");
        assertThat(f.dstPort()).isEqualTo(443);
        assertThat(f.bytes()).isEqualTo(9000);
        assertThat(f.packets()).isEqualTo(12);
        assertThat(f.tcpFlags()).isEqualTo(0x1b);
        assertThat(f.sampling()).isEqualTo(10);
        // uptime 100 s at export; flow from uptime 40 s to 90 s
        assertThat(f.start()).isEqualTo(now.minusSeconds(60));
        assertThat(f.end()).isEqualTo(now.minusSeconds(10));
    }

    @Test
    void v9DataBeforeItsTemplateIsSkippedThenDecoded() throws Exception {
        byte[] early = Packets.v9(false, now.getEpochSecond(), "10.0.0.1", "10.0.0.2", 5353, 53, 777);
        NetflowParser.Result r1 = parser.parse("192.0.2.1", early, early.length, now);
        assertThat(r1.flows()).isEmpty();
        assertThat(r1.skippedSets()).isEqualTo(1);
        byte[] p = Packets.v9(true, now.getEpochSecond(), "10.0.0.1", "10.0.0.2", 5353, 53, 777);
        NetflowParser.Result r2 = parser.parse("192.0.2.1", p, p.length, now);
        FlowRecord f = r2.flows().get(0);
        assertThat(f.protocol()).isEqualTo(17);
        assertThat(f.bytes()).isEqualTo(777);
        assertThat(f.tcpFlags()).isNull(); // UDP
        assertThat(f.start()).isEqualTo(now.minusSeconds(20)); // uptime 30 s, first 10 s
        // the template belongs to that exporter only
        byte[] again = Packets.v9(false, now.getEpochSecond(), "10.0.0.1", "10.0.0.2", 5353, 53, 777);
        assertThat(parser.parse("192.0.2.99", again, again.length, now).skippedSets()).isEqualTo(1);
    }

    @Test
    void decodesIpfixWithIpv6VariableLengthVendorFieldsAndSampling() throws Exception {
        long start = now.minusSeconds(30).toEpochMilli();
        byte[] p = Packets.ipfix(now.getEpochSecond(), start, now.toEpochMilli());
        NetflowParser.Result r = parser.parse("192.0.2.1", p, p.length, now);
        assertThat(r.version()).isEqualTo(10);
        assertThat(r.sampling()).isEqualTo(100);
        FlowRecord f = r.flows().get(0);
        assertThat(f.src().getHostAddress()).isEqualTo("2001:db8:0:0:0:0:0:10");
        assertThat(f.dstPort()).isEqualTo(443);
        assertThat(f.bytes()).isEqualTo(123_456);
        assertThat(f.start()).isEqualTo(Instant.ofEpochMilli(start));
        assertThat(f.sampling()).isEqualTo(100);
    }

    @Test
    void rejectsMalformedPackets() {
        byte[] p = Packets.v5(now.getEpochSecond(), "10.1.1.5", 1, "10.2.2.9", 2, 1, 1, 0);
        assertThatThrownBy(() -> parser.parse("x", p, 60, now)).isInstanceOf(NetflowParser.ParseException.class);
        byte[] v9 = Packets.v9(true, now.getEpochSecond(), "10.0.0.1", "10.0.0.2", 1, 2, 3);
        byte[] cut = Arrays.copyOf(v9, v9.length - 10);
        assertThatThrownBy(() -> parser.parse("x", cut, cut.length, now)).isInstanceOf(NetflowParser.ParseException.class);
        byte[] v7 = {0, 7, 0, 0};
        assertThatThrownBy(() -> parser.parse("x", v7, 4, now)).hasMessageContaining("unsupported version 7");
        byte[] ipfix = Packets.ipfix(now.getEpochSecond(), 0, 0);
        ipfix[3] = (byte) 0xff; // header length larger than the packet
        assertThatThrownBy(() -> parser.parse("x", ipfix, ipfix.length, now)).isInstanceOf(NetflowParser.ParseException.class);
    }
}
