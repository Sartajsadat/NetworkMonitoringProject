package gov.mot.netmon.agent.firewall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import gov.mot.netmon.agent.collect.CommandRunner;
import gov.mot.netmon.agent.model.Json;

/** Application policies: parsing, safety, nftables batch, Windows rules. Nothing touches a real firewall. */
class AppPolicyTest {

    @TempDir
    Path root;

    static AppPolicy parse(String kind, String identity, String mode, String dests) throws Exception {
        return AppPolicy.parse(Json.MAPPER.readTree("{\"ruleId\":\"" + UUID.randomUUID() + "\",\"tag\":\"ab12cd34\",\"kind\":\"APPLICATION\","
                + "\"identityKind\":\"" + kind + "\",\"identity\":" + Json.MAPPER.writeValueAsString(identity) + ",\"appMode\":\"" + mode
                + "\",\"destinations\":" + dests + ",\"mode\":\"ENFORCE\"}"));
    }

    @Test
    void parsesStrictlyAndRefusesCriticalIdentities() throws Exception {
        AppPolicy p = parse("LINUX_USER", "payroll", "ALLOW_ONLY", "[{\"cidr\":\"10.20.0.15/32\",\"protocol\":\"TCP\",\"port\":\"5432\"}]");
        assertEquals("payroll", p.identity());
        assertNull(p.forbidden("netmon-agent"));
        assertEquals("root", parse("LINUX_USER", "root", "BLOCK_INTERNET", "[]").forbidden("netmon-agent"));
        assertEquals("svc", parse("LINUX_USER", "svc", "BLOCK_INTERNET", "[]").forbidden("svc")); // the agent's own account
        assertThrows(IllegalArgumentException.class, () -> parse("LINUX_USER", "pay roll\" ; flush", "BLOCK_INTERNET", "[]"));
        assertThrows(IllegalArgumentException.class, () -> parse("WINDOWS_PROGRAM", "C:\\a\\b.exe\" -Action Allow", "BLOCK_INTERNET", "[]"));
        assertThrows(IllegalArgumentException.class, () -> parse("LINUX_USER", "app", "ALLOW_ONLY", "[]"));
        assertThrows(IllegalArgumentException.class, () -> parse("LINUX_USER", "app", "BLOCK_DESTINATIONS", "[{\"cidr\":\"example.com/32\"}]"));
        assertThrows(IllegalArgumentException.class, () -> parse("BUSINESS_NAME", "Payroll", "BLOCK_INTERNET", "[]"));
    }

    @Test
    void nftablesBatchHasItsOwnChainAndTheJumpLast() throws Exception {
        AppPolicy p = parse("LINUX_USER", "payroll", "ALLOW_ONLY",
                "[{\"cidr\":\"10.20.0.15/32\",\"protocol\":\"TCP\",\"port\":\"5432\"},{\"cidr\":\"2001:db8::/32\",\"protocol\":\"UDP\"}]");
        assertEquals("""
                add chain inet netmon app_ab12cd34
                add rule inet netmon app_ab12cd34 ct direction reply accept
                add rule inet netmon app_ab12cd34 ip daddr 127.0.0.0/8 accept
                add rule inet netmon app_ab12cd34 ip6 daddr ::1/128 accept
                add rule inet netmon app_ab12cd34 ip daddr 10.20.0.15/32 tcp dport 5432 accept
                add rule inet netmon app_ab12cd34 ip6 daddr 2001:db8::/32 meta l4proto udp accept
                add rule inet netmon app_ab12cd34 counter drop
                add rule inet netmon output meta skuid "payroll" jump app_ab12cd34 comment "netmon:ab12cd34"
                """, NftablesBackend.appBatch(p));
        String internet = NftablesBackend.appBatch(parse("LINUX_USER", "payroll", "BLOCK_INTERNET", "[]"));
        assertTrue(internet.contains("ip daddr 172.16.0.0/12 accept") && internet.contains("ip6 daddr fe80::/10 accept"));

        List<List<String>> calls = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        CommandRunner nft = args -> {
            calls.add(args);
            if (args.contains("-f")) scripts.add(Files.readString(Path.of(args.get(args.size() - 1))));
            if (args.contains("list")) return new CommandRunner.Result(0, """
                    {"nftables":[{"chain":{"family":"inet","table":"netmon","name":"output"}},
                     {"chain":{"family":"inet","table":"netmon","name":"app_ab12cd34"}},
                     {"rule":{"family":"inet","table":"netmon","chain":"output","handle":9,"comment":"netmon:ab12cd34"}}]}""");
            return new CommandRunner.Result(0, "");
        };
        NftablesBackend b = new NftablesBackend(nft, root, null);
        assertEquals(List.of("LINUX_USER"), b.applicationControl());
        b.checkApp(p);
        assertEquals("-c", calls.get(0).get(0));
        assertTrue(scripts.get(0).startsWith("table inet netmon {") && scripts.get(0).contains("jump app_ab12cd34"));
        assertEquals(Set.of("ab12cd34"), b.tags());
        assertTrue(b.remove("ab12cd34"));
        assertTrue(calls.contains(List.of("delete", "rule", "inet", "netmon", "output", "handle", "9")));
        assertEquals(List.of("delete", "chain", "inet", "netmon", "app_ab12cd34"), calls.get(calls.size() - 1));
        assertEquals(List.of(), new NftablesBackend(nft, root, "no permission").applicationControl());
    }

    @Test
    void windowsWritesAllowOnlyAsABlockOfEverythingElse() throws Exception {
        assertEquals(List.of("0.0.0.0-9.255.255.255", "11.0.0.0-255.255.255.255", "::-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"),
                AppPolicy.complement(List.of("10.0.0.0/8")));
        assertEquals(List.of("0.0.0.0-126.255.255.255", "128.0.0.0-255.255.255.255", "::-::", "::2-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"),
                AppPolicy.complement(List.of("127.0.0.0/8", "::1/128")));
        AppPolicy p = parse("WINDOWS_PROGRAM", "C:\\Program Files\\Payroll\\payroll.exe", "ALLOW_ONLY",
                "[{\"cidr\":\"10.20.0.0/16\",\"protocol\":\"ANY\"}]");
        List<Map<String, String>> envs = new ArrayList<>();
        WindowsFirewallBackend b = new WindowsFirewallBackend(env -> {
            envs.add(env);
            return Json.MAPPER.readTree("{\"ok\":true,\"message\":\"1 rule(s)\"}");
        }, null);
        b.applyApp(p);
        Map<String, String> env = envs.get(0);
        assertEquals("apply-app", env.get("NETMON_FW_OP"));
        assertEquals("C:\\Program Files\\Payroll\\payroll.exe", env.get("NETMON_FW_APP_VALUE"));
        String rules = env.get("NETMON_FW_APP_RULES");
        assertTrue(rules.contains("10.19.255.255") && rules.contains("10.21.0.0") && !rules.contains("10.20.0.1-"), rules);
        assertTrue(b.describeApp(p).startsWith("New-NetFirewallRule -Name netmon-ab12cd34-1 -Group NetMon -Direction Outbound -Action Block -Program"));
        AppPolicy ports = parse("WINDOWS_SERVICE", "W3SVC", "ALLOW_ONLY", "[{\"cidr\":\"10.0.0.0/8\",\"protocol\":\"TCP\",\"port\":\"443\"}]");
        assertThrows(FirewallBackend.FirewallException.class, () -> b.applyApp(ports));
        AppPolicy linux = parse("LINUX_USER", "app", "BLOCK_INTERNET", "[]");
        assertThrows(FirewallBackend.FirewallException.class, () -> b.applyApp(linux));
        String script = WindowsFirewallBackend.load();
        assertTrue(script.contains("ConvertFrom-Json") && !script.contains("Invoke-Expression"));
        assertFalse(new UfwBackend(args -> new CommandRunner.Result(0, ""), "/usr/sbin/ufw", null).applicationControl().contains("LINUX_USER"));
    }
}
