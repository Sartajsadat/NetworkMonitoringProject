package gov.mot.netmon.firewall;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import gov.mot.netmon.firewall.AppPolicyValidator.Destination;

class AppPolicyValidatorTest {

    static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    static List<String> codes(List<RuleValidator.Issue> l) {
        return l.stream().map(RuleValidator.Issue::code).toList();
    }

    @Test
    void identitiesMustBeEnforceableOnTheServersOs() {
        assertThat(AppPolicyValidator.identityProblem("LINUX_USER", "www-data", "LINUX")).isNull();
        assertThat(AppPolicyValidator.identityProblem("LINUX_USER", "www data", "LINUX")).isNotNull();
        assertThat(AppPolicyValidator.identityProblem("LINUX_USER", "nginx", "WINDOWS")).contains("does not exist");
        assertThat(AppPolicyValidator.identityProblem("WINDOWS_PROGRAM", "C:\\Program Files\\App\\app.exe", "WINDOWS")).isNull();
        assertThat(AppPolicyValidator.identityProblem("WINDOWS_PROGRAM", "app.exe", "WINDOWS")).contains("full path");
        assertThat(AppPolicyValidator.identityProblem("WINDOWS_PROGRAM", "C:\\x\\a\"; calc.exe", "WINDOWS")).isNotNull();
        assertThat(AppPolicyValidator.identityProblem("WINDOWS_SERVICE", "W3SVC", "WINDOWS")).isNull();
        assertThat(AppPolicyValidator.identityProblem("BUSINESS_NAME", "Payroll", "LINUX")).isNotNull();
    }

    @Test
    void validatesModesDestinationsAndDangerousIdentities() {
        var c = AppPolicyValidator.check("ALLOW_ONLY", "LINUX_USER", "app", List.of(), null, NOW, false);
        assertThat(codes(c.errorIssues())).contains("APP_NEED_DESTINATIONS");
        c = AppPolicyValidator.check("BLOCK_INTERNET", "LINUX_USER", "root", List.of(), null, NOW, false);
        assertThat(codes(c.errorIssues())).contains("APP_FORBIDDEN_IDENTITY");
        c = AppPolicyValidator.check("BLOCK_DESTINATIONS", "LINUX_USER", "app", List.of(new Destination("10.0.0.5/24", "TCP", "443")), null, NOW, false);
        assertThat(codes(c.errorIssues())).contains("ADDRESS");
        c = AppPolicyValidator.check("BLOCK_DESTINATIONS", "LINUX_USER", "app", List.of(new Destination("10.0.0.5", "ANY", "443")), null, NOW, false);
        assertThat(codes(c.errorIssues())).contains("PORT_PROTOCOL");
        c = AppPolicyValidator.check("ALLOW_ONLY", "WINDOWS_PROGRAM", "C:\\a\\b.exe", List.of(new Destination("10.0.0.0/8", "TCP", "443")), null, NOW, false);
        assertThat(codes(c.errorIssues())).contains("APP_WINDOWS_PORTS");
        c = AppPolicyValidator.check("ALLOW_ONLY", "LINUX_USER", "app", List.of(new Destination("10.1.2.3", "TCP", "5432")), null, NOW, true);
        assertThat(c.ok()).isTrue();
        assertThat(codes(c.warningIssues())).containsExactly("APP_SHARED_USER", "APP_NO_DNS", "NO_EXPIRY", "DRY_RUN");
        assertThat(c.destinations()).containsExactly(new Destination("10.1.2.3/32", "TCP", "5432"));
    }

    @Test
    void previewsTheNftablesBatchAndWindowsRules() {
        String nft = AppPolicyValidator.nftables("ALLOW_ONLY", "app", List.of(new Destination("10.1.2.3/32", "TCP", "5432")), "ab12cd34");
        assertThat(nft).contains("add chain inet netmon app_ab12cd34")
                .contains("add rule inet netmon output meta skuid \"app\" jump app_ab12cd34 comment \"netmon:ab12cd34\"")
                .contains("add rule inet netmon app_ab12cd34 ct direction reply accept")
                .contains("ip daddr 10.1.2.3/32 tcp dport 5432 accept")
                .endsWith("add rule inet netmon app_ab12cd34 counter drop");
        String internet = AppPolicyValidator.nftables("BLOCK_INTERNET", "app", List.of(), "t1");
        assertThat(internet).contains("ip daddr 192.168.0.0/16 accept").contains("ip6 daddr fc00::/7 accept").endsWith("counter drop");
        String block = AppPolicyValidator.nftables("BLOCK_DESTINATIONS", "app", List.of(new Destination("203.0.113.0/24", "ANY", null)), "t2");
        assertThat(block).contains("ip daddr 203.0.113.0/24 counter drop").doesNotContain("app_t2 counter drop");
        assertThat(AppPolicyValidator.windows("BLOCK_DESTINATIONS", "WINDOWS_SERVICE", "W3SVC",
                List.of(new Destination("203.0.113.0/24", "TCP", "25")), "t3"))
                .isEqualTo("New-NetFirewallRule -Group NetMon -Direction Outbound -Action Block -Service W3SVC -Name netmon-t3-1 "
                        + "-RemoteAddress 203.0.113.0/24 -Protocol TCP -RemotePort 25");
    }
}
