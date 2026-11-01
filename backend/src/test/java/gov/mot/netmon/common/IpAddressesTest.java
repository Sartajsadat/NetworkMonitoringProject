package gov.mot.netmon.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class IpAddressesTest {

    @ParameterizedTest
    @CsvSource({
        "10.0.0.1, 10.0.0.1, 4",
        "255.255.255.255, 255.255.255.255, 4",
        "2001:db8::1, 2001:db8::1, 6",
        "2001:0DB8:0:0:0:0:0:1, 2001:db8::1, 6",
        "::1, ::1, 6",
        "fe80:0:0:0:1:0:0:1, fe80::1:0:0:1, 6",
        "::ffff:10.1.2.3, 10.1.2.3, 4"
    })
    void parsesAndCanonicalises(String input, String canonical, int version) {
        IpAddresses.ParsedIp p = IpAddresses.parse(input);
        assertThat(p.canonical()).isEqualTo(canonical);
        assertThat(p.version()).isEqualTo(version);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "256.1.1.1", "1.2.3", "01.2.3.4", "example.com", "localhost", "1.2.3.4/24",
        "2001:db8::g", "1.2.3.4; rm -rf /", ":::"})
    void rejectsInvalid(String input) {
        assertThatThrownBy(() -> IpAddresses.parse(input)).isInstanceOf(IllegalArgumentException.class);
    }
}
