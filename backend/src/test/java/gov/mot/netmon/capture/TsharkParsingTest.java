package gov.mot.netmon.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import gov.mot.netmon.common.ApiException;

class TsharkParsingTest {

    @Test
    void parsesSummaryLineWithIpv4Tcp() {
        String line = String.join("\t", "14", "1793549693.673108359", "127.0.0.1", "127.0.0.1", "", "", "18081", "54094", "", "",
                "54", "TCP", "0x0014", "", "True", "", "18081 → 54094 [RST, ACK] Seq=1", "", "");
        Tshark.SummaryRow r = Tshark.parseSummary(line);
        assertThat(r.frame()).isEqualTo(14);
        assertThat(r.reset()).isTrue();
        assertThat(r.retransmission()).isFalse();
        assertThat(r.srcPort()).isEqualTo(18081);
        assertThat(r.time().getNano()).isEqualTo(673108359);
    }

    @Test
    void parsesIpv6UdpAndArp() {
        String v6 = String.join("\t", "1", "1.5", "", "", "fe80::1", "ff02::fb", "", "", "5353", "5353", "90", "MDNS", "", "", "",
                "host.local", "Standard query", "", "");
        assertThat(Tshark.parseSummary(v6).src()).isEqualTo("fe80::1");
        assertThat(Tshark.parseSummary(v6).dnsQuery()).isEqualTo("host.local");
        String arp = String.join("\t", "2", "2.0", "", "", "", "", "", "", "", "", "42", "ARP", "", "", "", "", "Who has 10.0.0.1?",
                "10.0.0.5", "10.0.0.1");
        assertThat(Tshark.parseSummary(arp).src()).isEqualTo("10.0.0.5");
        assertThat(Tshark.parseSummary("garbage")).isNull();
    }

    @Test
    void displayFilterTextIsCheckedBeforeTsharkRuns() {
        assertThatThrownBy(() -> Tshark.checkFilterText("ip.addr==1.2.3.4\n-w /tmp/x")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Tshark.checkFilterText(" ")).isInstanceOf(ApiException.class);
        assertThat(Tshark.checkFilterText(" tcp.port == 80 ")).isEqualTo("tcp.port == 80");
    }

    @Test
    void pdmlParserBuildsTreeAndRejectsExternalEntities() {
        String pdml = """
                <?xml version="1.0" encoding="utf-8"?>
                <pdml><packet>
                  <proto name="geninfo" showname="General information"/>
                  <proto name="ip" showname="Internet Protocol Version 4, Src: 10.0.0.1">
                    <field name="ip.src" showname="Source Address: 10.0.0.1" show="10.0.0.1"/>
                    <field name="ip.hidden" showname="Hidden" hide="yes" show="x"/>
                  </proto>
                </packet></pdml>""";
        var tree = PdmlTree.parse(pdml);
        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).label()).startsWith("Internet Protocol Version 4");
        assertThat(tree.get(0).children()).extracting(PdmlTree.TreeNode::label).containsExactly("Source Address: 10.0.0.1");

        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE pdml [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <pdml><packet><proto name="ip" showname="&x;"/></packet></pdml>""";
        assertThatThrownBy(() -> PdmlTree.parse(xxe)).isInstanceOf(IllegalStateException.class);
    }
}
