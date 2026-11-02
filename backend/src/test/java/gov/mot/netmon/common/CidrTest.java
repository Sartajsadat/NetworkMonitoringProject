package gov.mot.netmon.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

class CidrTest {

    @Test
    void parsesIpv4AndIpv6Networks() {
        Cidr c = Cidr.parse(" 10.20.0.0/16 ");
        assertThat(c.network()).isEqualTo("10.20.0.0/16");
        assertThat(c.contains("10.20.255.254")).isTrue();
        assertThat(c.contains("10.21.0.1")).isFalse();
        assertThat(c.contains("fe80::1")).isFalse();
        assertThat(c.usable()).isEqualTo(BigInteger.valueOf(65534));
        assertThat(Cidr.parse("192.168.1.0/31").usable()).isEqualTo(BigInteger.TWO);
        Cidr v6 = Cidr.parse("2001:DB8:0:0::/48");
        assertThat(v6.network()).isEqualTo("2001:db8::/48");
        assertThat(v6.contains("2001:db8:0:ffff::1")).isTrue();
        assertThat(v6.contains("2001:db9::1")).isFalse();
        assertThat(Cidr.parse("0.0.0.0/0").contains("8.8.8.8")).isTrue();
    }

    @Test
    void rejectsHostBitsBadPrefixesAndNames() {
        assertThatThrownBy(() -> Cidr.parse("10.0.0.5/24")).hasMessageContaining("10.0.0.0/24");
        assertThatThrownBy(() -> Cidr.parse("10.0.0.0/33")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cidr.parse("10.0.0.0")).hasMessageContaining("CIDR");
        assertThatThrownBy(() -> Cidr.parse("example.gov.af/24")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cidr.parse("10.0.0.0/x")).isInstanceOf(IllegalArgumentException.class);
    }
}
