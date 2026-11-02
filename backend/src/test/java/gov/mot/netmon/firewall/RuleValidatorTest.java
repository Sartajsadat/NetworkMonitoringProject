package gov.mot.netmon.firewall;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class RuleValidatorTest {

    static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    static final List<String> PROTECTED = List.of("127.0.0.0/8", "10.0.0.5/32");

    static RuleValidator.Check check(String action, String dir, String remote, String proto, String lport, Instant exp) {
        return RuleValidator.check(new RuleValidator.Input(action, dir, remote, null, proto, lport, null), exp, NOW, PROTECTED,
                "192.168.50.7", false);
    }

    @Test
    void normalisesAddressesAndPorts() {
        RuleValidator.Check c = check("block", "inbound", "203.0.113.9", "tcp", "8000-8080", NOW.plus(Duration.ofDays(1)));
        assertThat(c.errors()).isEmpty();
        assertThat(c.spec().remoteCidr()).isEqualTo("203.0.113.9/32");
        assertThat(c.spec().localPort()).isEqualTo("8000-8080");
        assertThat(check("BLOCK", "INBOUND", "2001:DB8::1", "ANY", null, null).spec().remoteCidr()).isEqualTo("2001:db8::1/128");
    }

    @Test
    void refusesInvalidAndDangerousRules() {
        assertThat(check("BLOCK", "INBOUND", "10.0.0.5/24", "ANY", null, null).errors()).anyMatch(e -> e.contains("10.0.0.0/24"));
        assertThat(check("BLOCK", "INBOUND", "203.0.113.1", "ICMP", "22", null).errors()).contains("Ports can only be used with TCP or UDP");
        assertThat(check("BLOCK", "INBOUND", "203.0.113.1", "TCP", "70000", null).errors()).isNotEmpty();
        assertThat(check("BLOCK", "SIDEWAYS", "203.0.113.1", "TCP", null, null).errors()).isNotEmpty();
        assertThat(check("BLOCK", "INBOUND", "0.0.0.0/0", "ANY", null, null).errors()).anyMatch(e -> e.contains("cut the target off"));
        // Protected management/monitoring addresses, also when a wider range covers them.
        assertThat(check("BLOCK", "INBOUND", "10.0.0.0/8", "TCP", "443", null).errors()).anyMatch(e -> e.contains("10.0.0.5/32"));
        assertThat(check("BLOCK", "OUTBOUND", "127.0.0.1", "ANY", null, null).errors()).anyMatch(e -> e.contains("127.0.0.0/8"));
        // Allowing a protected address is fine.
        assertThat(check("ALLOW", "INBOUND", "10.0.0.5", "ANY", null, null).errors()).isEmpty();
        assertThat(check("BLOCK", "INBOUND", "203.0.113.1", "TCP", null, NOW.minusSeconds(1)).errors()).contains("Expiry must be in the future");
        assertThat(check("BLOCK", "INBOUND", "203.0.113.1", "TCP", null, NOW.plus(Duration.ofDays(400))).errors()).isNotEmpty();
    }

    @Test
    void warnsAboutRisksThatNeedAcknowledgement() {
        RuleValidator.Check own = check("BLOCK", "INBOUND", "192.168.50.0/24", "TCP", "443", NOW.plusSeconds(3600));
        assertThat(own.ok()).isTrue();
        assertThat(own.warnings()).anyMatch(w -> w.contains("you are connected from"));
        RuleValidator.Check ssh = check("BLOCK", "INBOUND", "172.16.0.0/12", "TCP", "22", NOW.plusSeconds(3600));
        assertThat(ssh.warnings()).anyMatch(w -> w.contains("port 22"));
        assertThat(check("BLOCK", "INBOUND", "203.0.113.1", "TCP", "443", null).warnings()).anyMatch(w -> w.contains("No expiry"));
        assertThat(check("BLOCK", "INBOUND", "203.0.113.1", "TCP", "443", NOW.plusSeconds(60)).warnings()).isEmpty();
        RuleValidator.Check dry = RuleValidator.check(new RuleValidator.Input("BLOCK", "INBOUND", "203.0.113.1", null, "ANY", null, null),
                NOW.plusSeconds(60), NOW, PROTECTED, null, true);
        assertThat(dry.warnings()).anyMatch(w -> w.startsWith("Dry run"));
    }

    @Test
    void previewsTheExactCommandForEachFirewall() {
        RuleSpec in = check("BLOCK", "INBOUND", "203.0.113.0/24", "TCP", "443", null).spec();
        assertThat(RuleValidator.nftables(in, "ab12cd34"))
                .isEqualTo("add rule inet netmon input ip saddr 203.0.113.0/24 tcp dport 443 counter drop comment \"netmon:ab12cd34\"");
        assertThat(RuleValidator.ufw(in, "ab12cd34"))
                .isEqualTo("ufw insert 1 deny proto tcp from 203.0.113.0/24 to any port 443 comment 'netmon:ab12cd34'");
        assertThat(RuleValidator.windows(in, "ab12cd34")).isEqualTo(
                "New-NetFirewallRule -Name netmon-ab12cd34 -Group NetMon -Direction Inbound -Action Block -RemoteAddress 203.0.113.0/24 -Protocol TCP -LocalPort 443");
        RuleSpec out = check("BLOCK", "OUTBOUND", "2001:db8::/32", "ICMP", null, null).spec();
        assertThat(RuleValidator.nftables(out, "x1")).isEqualTo(
                "add rule inet netmon output ip6 daddr 2001:db8::/32 meta l4proto ipv6-icmp counter drop comment \"netmon:x1\"");
        assertThat(RuleValidator.ufw(out, "x1")).startsWith("ufw insert 1 deny out proto icmp from any to 2001:db8::/32");
        RuleSpec allow = check("ALLOW", "INBOUND", "198.51.100.7", "UDP", null, null).spec();
        assertThat(RuleValidator.nftables(allow, "t")).isEqualTo(
                "insert rule inet netmon input ip saddr 198.51.100.7/32 meta l4proto udp counter accept comment \"netmon:t\"");
        assertThat(RuleValidator.preview(allow, "t", null)).startsWith("(no supported firewall detected");
    }
}
