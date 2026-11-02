package gov.mot.netmon.agent.firewall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import gov.mot.netmon.agent.collect.CommandRunner;
import gov.mot.netmon.agent.model.Json;
import gov.mot.netmon.agent.model.Report;
import gov.mot.netmon.agent.transport.PlatformClient;

/** Firewall manager against a local fake platform and an in-memory firewall. Nothing touches the real firewall. */
class FirewallManagerTest {

    @TempDir
    Path root;
    HttpServer server;
    final ConcurrentLinkedQueue<String> tasks = new ConcurrentLinkedQueue<>();
    final List<JsonNode> statuses = new CopyOnWriteArrayList<>();
    final AtomicInteger statusCode = new AtomicInteger(200);
    final FakeFirewall fw = new FakeFirewall();
    Instant now = Instant.parse("2026-11-02T10:00:00Z");

    /** In-memory firewall. */
    static final class FakeFirewall implements FirewallBackend {
        final Set<String> present = new HashSet<>();
        final List<String> calls = new ArrayList<>();
        String problem;
        boolean swallow; // apply "succeeds" but the rule never shows up
        boolean rejectCheck;
        List<String> appControl = List.of();

        public List<String> applicationControl() { return appControl; }
        public void checkApp(AppPolicy p) { calls.add("checkApp " + p.tag()); }
        public void applyApp(AppPolicy p) { calls.add("applyApp " + p.tag()); present.add(p.tag()); }

        public String name() { return "NFTABLES"; }
        public String problem() { return problem; }
        public String describe(FwRule r) { return "nft " + NftablesBackend.rule(r); }
        public void check(FwRule r) throws FirewallException {
            calls.add("check " + r.tag());
            if (rejectCheck) throw new FirewallException("nft rejected the rule: Error: syntax");
        }
        public void apply(FwRule r) {
            calls.add("apply " + r.tag());
            if (!swallow) present.add(r.tag());
        }
        public Set<String> tags() { return Set.copyOf(present); }
        public boolean remove(String tag) {
            calls.add("remove " + tag);
            return present.remove(tag);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/agent/tasks", ex -> {
            String list = String.join(",", tasks);
            tasks.clear();
            respond(ex, 200, "{\"tasks\":[" + list + "]}");
        });
        server.createContext("/api/v1/agent/firewall/rules/", ex -> {
            ObjectNode n = (ObjectNode) Json.MAPPER.readTree(ex.getRequestBody());
            String path = ex.getRequestURI().getPath();
            n.put("ruleId", path.split("/")[6]);
            if (statusCode.get() == 200) statuses.add(n);
            respond(ex, statusCode.get(), "{}");
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    FirewallManager manager(boolean enabled, boolean enforcement, List<String> sessions) throws Exception {
        Properties p = new Properties();
        p.setProperty("firewall.enabled", Boolean.toString(enabled));
        p.setProperty("firewall.enforcement", Boolean.toString(enforcement));
        p.setProperty("firewall.protected-addresses", "10.9.0.0/16");
        FirewallSettings s = FirewallSettings.fromProperties(p, root, false);
        PlatformClient client = new PlatformClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null);
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return now; }
        };
        FirewallManager m = new FirewallManager(s, () -> fw, client, () -> "Agent a:b", clock, List.of("192.0.2.10/32"), () -> sessions);
        m.setSelfPoll(true);
        return m;
    }

    String task(String type, UUID id, String tag, String mode, String remote, String extra) {
        return "{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"" + type + "\",\"firewall\":{\"ruleId\":\"" + id + "\",\"tag\":\"" + tag
                + "\",\"action\":\"BLOCK\",\"direction\":\"INBOUND\",\"remote\":\"" + remote + "\",\"protocol\":\"TCP\",\"localPort\":\"443\",\"mode\":\""
                + mode + "\"" + extra + "}}";
    }

    JsonNode last() {
        return statuses.get(statuses.size() - 1);
    }

    @Test
    void enforcedRuleIsAppliedOnlyAfterReadBackAndRemovedOnRequest() throws Exception {
        FirewallManager m = manager(true, true, List.of());
        UUID id = UUID.randomUUID();
        tasks.add(task("APPLY_FIREWALL_RULE", id, "ab12cd34", "ENFORCE", "203.0.113.0/24", ""));
        m.tick();
        assertEquals("APPLIED", last().get("state").asText());
        assertTrue(last().get("command").asText().contains("ip saddr 203.0.113.0/24 tcp dport 443 counter drop comment \"netmon:ab12cd34\""));
        assertEquals(List.of("ab12cd34"), m.capability().ruleTags());
        assertTrue(m.capability().enforcementAllowed());
        assertEquals(List.of("192.0.2.10/32", "10.9.0.0/16"), m.capability().protectedAddresses());

        // Applying again is idempotent.
        tasks.add(task("APPLY_FIREWALL_RULE", id, "ab12cd34", "ENFORCE", "203.0.113.0/24", ""));
        m.tick();
        assertEquals(1, fw.calls.stream().filter(c -> c.startsWith("apply")).count());

        tasks.add("{\"type\":\"REMOVE_FIREWALL_RULE\",\"firewall\":{\"ruleId\":\"" + id + "\",\"tag\":\"ab12cd34\"}}");
        m.tick();
        assertEquals("REMOVED", last().get("state").asText());
        assertTrue(fw.present.isEmpty());
        tasks.add("{\"type\":\"REMOVE_FIREWALL_RULE\",\"firewall\":{\"ruleId\":\"" + id + "\",\"tag\":\"ab12cd34\"}}");
        m.tick();
        assertEquals("NOT_PRESENT", last().get("state").asText());
    }

    @Test
    void ruleThatCannotBeReadBackIsUnverifiedNotApplied() throws Exception {
        FirewallManager m = manager(true, true, List.of());
        fw.swallow = true;
        tasks.add(task("APPLY_FIREWALL_RULE", UUID.randomUUID(), "ab12cd35", "ENFORCE", "203.0.113.5/32", ""));
        m.tick();
        assertEquals("UNVERIFIED", last().get("state").asText());
    }

    @Test
    void withoutLocalConsentEverythingIsADryRun() throws Exception {
        FirewallManager m = manager(true, false, List.of());
        tasks.add(task("APPLY_FIREWALL_RULE", UUID.randomUUID(), "ab12cd36", "ENFORCE", "203.0.113.0/24", ""));
        m.tick();
        assertEquals("DRY_RUN", last().get("state").asText());
        assertTrue(last().get("message").asText().contains("firewall.enforcement=false"));
        assertTrue(fw.present.isEmpty());
        assertEquals(List.of("check ab12cd36"), fw.calls);
        assertFalse(m.capability().enforcementAllowed());

        fw.rejectCheck = true;
        tasks.add(task("APPLY_FIREWALL_RULE", UUID.randomUUID(), "ab12cd37", "DRY_RUN", "203.0.113.0/24", ""));
        m.tick();
        assertEquals("FAILED", last().get("state").asText());

        // Firewall control switched off entirely: the platform hears why at once.
        FirewallManager off = manager(false, false, List.of());
        tasks.add(task("APPLY_FIREWALL_RULE", UUID.randomUUID(), "ab12cd38", "ENFORCE", "203.0.113.0/24", ""));
        off.tick();
        assertEquals("FAILED", last().get("state").asText());
        assertTrue(last().get("message").asText().contains("firewall.enabled=false"));
        assertFalse(off.capability().enabled());
        assertNull(off.capability().ruleTags());
    }

    @Test
    void localSafetyRefusesProtectedAddressesAndLiveManagementSessions() throws Exception {
        FirewallManager m = manager(true, true, List.of("198.51.100.23"));
        tasks.add(task("APPLY_FIREWALL_RULE", UUID.randomUUID(), "aa000001", "ENFORCE", "198.51.100.0/24", ""));
        tasks.add(task("APPLY_FIREWALL_RULE", UUID.randomUUID(), "aa000002", "ENFORCE", "192.0.2.0/24", "")); // platform
        tasks.add(task("APPLY_FIREWALL_RULE", UUID.randomUUID(), "aa000003", "ENFORCE", "10.9.4.0/24", "")); // configured
        tasks.add(task("APPLY_FIREWALL_RULE", UUID.randomUUID(), "aa000004", "ENFORCE", "127.0.0.0/8", "")); // loopback
        m.tick();
        assertEquals(4, statuses.size());
        statuses.forEach(s -> assertEquals("FAILED", s.get("state").asText()));
        assertTrue(statuses.get(0).get("message").asText().contains("management session from 198.51.100.23"));
        assertTrue(statuses.get(1).get("message").asText().contains("192.0.2.10/32"));
        assertTrue(fw.present.isEmpty());
        assertTrue(fw.calls.isEmpty());
    }

    @Test
    void refusesMalformedOrInjectedRules() throws Exception {
        FirewallManager m = manager(true, true, List.of());
        tasks.add(task("APPLY_FIREWALL_RULE", UUID.randomUUID(), "ab12cd39", "ENFORCE", "203.0.113.0/24; flush ruleset", ""));
        tasks.add(task("APPLY_FIREWALL_RULE", UUID.randomUUID(), "ab12cd40", "ENFORCE", "example.com/32", ""));
        tasks.add(task("APPLY_FIREWALL_RULE", UUID.randomUUID(), "x\\\" ; drop", "ENFORCE", "203.0.113.0/24", ""));
        tasks.add(task("APPLY_FIREWALL_RULE", UUID.randomUUID(), "ab12cd41", "ENFORCE", "203.0.113.5/24", ""));
        m.tick();
        assertEquals(4, statuses.size());
        statuses.forEach(s -> assertEquals("FAILED", s.get("state").asText()));
        assertTrue(fw.calls.isEmpty());
    }

    @Test
    void expiredRulesAreRemovedLocallyEvenWhenThePlatformIsUnreachable() throws Exception {
        FirewallManager m = manager(true, true, List.of());
        UUID id = UUID.randomUUID();
        tasks.add(task("APPLY_FIREWALL_RULE", id, "ab12cd42", "ENFORCE", "203.0.113.0/24", ",\"expiresAt\":\"2026-11-02T11:00:00Z\""));
        m.tick();
        assertEquals("APPLIED", last().get("state").asText());
        assertTrue(Files.exists(root.resolve("firewall-rules.json")));

        server.stop(0); // platform gone
        now = Instant.parse("2026-11-02T11:00:01Z");
        FirewallManager restarted = manager(true, true, List.of()); // state survives a restart
        restarted.setSelfPoll(false);
        restarted.tick();
        assertTrue(fw.present.isEmpty());
        assertTrue(restarted.state().find("ab12cd42").removedAt() != null);
    }

    @Test
    void resultsAreRetriedUntilThePlatformAcceptsThem() throws Exception {
        FirewallManager m = manager(true, true, List.of());
        statusCode.set(503);
        tasks.add(task("APPLY_FIREWALL_RULE", UUID.randomUUID(), "ab12cd43", "ENFORCE", "203.0.113.0/24", ""));
        m.tick();
        assertEquals(1, m.pending().size());
        statusCode.set(200);
        m.tick();
        assertEquals(0, m.pending().size());
        assertEquals("APPLIED", last().get("state").asText());
    }

    @Test
    void applicationPoliciesAreAppliedVerifiedOrRefusedHonestly() throws Exception {
        FirewallManager m = manager(true, true, List.of());
        UUID id = UUID.randomUUID();
        String app = "{\"type\":\"APPLY_FIREWALL_RULE\",\"firewall\":{\"ruleId\":\"" + id + "\",\"tag\":\"cc000001\",\"kind\":\"APPLICATION\","
                + "\"identityKind\":\"LINUX_USER\",\"identity\":\"payroll\",\"appMode\":\"BLOCK_INTERNET\",\"destinations\":[],\"mode\":\"ENFORCE\"}}";
        tasks.add(app); // the fake firewall cannot match applications
        m.tick();
        assertEquals("FAILED", last().get("state").asText());
        assertTrue(last().get("message").asText().startsWith("Not supported here"));
        assertTrue(fw.present.isEmpty());
        assertEquals(List.of(), m.capability().applicationControl());

        fw.appControl = List.of("LINUX_USER");
        tasks.add(app);
        m.tick();
        assertEquals("APPLIED", last().get("state").asText());
        assertEquals(List.of("LINUX_USER"), m.capability().applicationControl());
        tasks.add(app.replace("payroll", "root").replace("cc000001", "cc000002"));
        m.tick();
        assertEquals("FAILED", last().get("state").asText());
        assertTrue(last().get("message").asText().contains("restricting root"));
    }

    // ------------------------------------------------------------------ backends

    @Test
    void nftablesWritesItsOwnTableAndReadsRulesBackFromJson() throws Exception {
        List<List<String>> calls = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        CommandRunner nft = args -> {
            calls.add(args);
            if (args.contains("-f")) scripts.add(Files.readString(Path.of(args.get(args.size() - 1))));
            if (args.contains("list")) {
                return new CommandRunner.Result(0, """
                        {"nftables": [{"metainfo": {"version": "1.0.9"}}, {"table": {"family": "inet", "name": "netmon", "handle": 3}},
                         {"chain": {"family": "inet", "table": "netmon", "name": "input", "handle": 1}},
                         {"rule": {"family": "inet", "table": "netmon", "chain": "input", "handle": 4, "comment": "netmon:ab12cd34", "expr": []}},
                         {"rule": {"family": "inet", "table": "netmon", "chain": "input", "handle": 5, "comment": "something else", "expr": []}}]}""");
            }
            return new CommandRunner.Result(0, "");
        };
        NftablesBackend b = new NftablesBackend(nft, root.resolve("tmp"), null);
        FwRule r = FwRule.parse(Json.MAPPER.readTree(task("x", UUID.randomUUID(), "ab12cd34", "ENFORCE", "2001:DB8::/32", "").replace("{\"id", "{\"x").replaceAll("^.*\"firewall\":", "").replaceAll("}$", "")));
        b.check(r);
        assertEquals(List.of("-c", "-f"), calls.get(0).subList(0, 2));
        assertTrue(scripts.get(0).startsWith("table inet netmon {"));
        assertTrue(scripts.get(0).contains("add rule inet netmon input ip6 saddr 2001:db8::/32 tcp dport 443 counter drop comment \"netmon:ab12cd34\""));
        b.apply(r);
        assertEquals("-f", calls.get(1).get(0));
        assertEquals(Set.of("ab12cd34"), b.tags());
        assertTrue(b.remove("ab12cd34"));
        assertTrue(calls.contains(List.of("delete", "rule", "inet", "netmon", "input", "handle", "4")));
        try (var files = Files.list(root.resolve("tmp"))) {
            assertEquals(0, files.count()); // temporary scripts are deleted
        }
        CommandRunner missing = args -> new CommandRunner.Result(1, "Error: No such file or directory\nlist table inet netmon");
        assertEquals(Set.of(), new NftablesBackend(missing, root, null).tags());
        CommandRunner bad = args -> new CommandRunner.Result(1, "/tmp/x.nft:5:1-3: Error: Could not process rule: Operation not permitted");
        FirewallBackend.FirewallException e = assertThrows(FirewallBackend.FirewallException.class, () -> new NftablesBackend(bad, root, null).apply(r));
        assertTrue(e.getMessage().contains("Operation not permitted"));
    }

    @Test
    void ufwPrependsCommentedRulesThroughSudoAndDeletesByNumber() throws Exception {
        List<List<String>> calls = new ArrayList<>();
        String[] status = {"""
                Status: active

                     To                         Action      From
                     --                         ------      ----
                [ 1] 443/tcp                    DENY IN     203.0.113.0/24             # netmon:ab12cd34
                [ 2] 22/tcp                     ALLOW IN    Anywhere
                """};
        CommandRunner sudo = args -> {
            calls.add(args);
            if (args.contains("status")) return new CommandRunner.Result(0, status[0]);
            if (args.contains("delete")) status[0] = "Status: active\n[ 1] 22/tcp ALLOW IN Anywhere\n";
            return new CommandRunner.Result(0, "Rule inserted\n");
        };
        UfwBackend b = new UfwBackend(sudo, "/usr/sbin/ufw", null);
        FwRule r = new FwRule(UUID.randomUUID(), "ab12cd34", "BLOCK", "INBOUND", "203.0.113.0/24", null, "TCP", "8000-8080", null, "ENFORCE", null);
        assertEquals(List.of("prepend", "deny", "proto", "tcp", "from", "203.0.113.0/24", "to", "any", "port", "8000:8080", "comment",
                "netmon:ab12cd34"), UfwBackend.ruleArgs(r));
        b.check(r);
        assertEquals(List.of("-n", "/usr/sbin/ufw", "--dry-run", "prepend"), calls.get(0).subList(0, 4));
        assertEquals(Set.of("ab12cd34"), b.tags());
        assertTrue(b.remove("ab12cd34"));
        assertTrue(calls.stream().anyMatch(c -> c.equals(List.of("-n", "/usr/sbin/ufw", "--force", "delete", "1"))));
        assertFalse(b.remove("ab12cd34"));
        FwRule out = new FwRule(UUID.randomUUID(), "ab12cd35", "BLOCK", "OUTBOUND", "198.51.100.7/32", null, "ANY", null, null, "ENFORCE", null);
        assertEquals(List.of("prepend", "deny", "out", "from", "any", "to", "198.51.100.7/32", "comment", "netmon:ab12cd35"), UfwBackend.ruleArgs(out));
        status[0] = "Status: inactive\n";
        assertThrows(FirewallBackend.FirewallException.class, b::tags);
    }

    @Test
    void windowsPassesValidatedFieldsThroughEnvironmentOnly() throws Exception {
        List<Map<String, String>> envs = new ArrayList<>();
        WindowsFirewallBackend b = new WindowsFirewallBackend(env -> {
            envs.add(env);
            return Json.MAPPER.readTree("state".equals(env.get("NETMON_FW_OP"))
                    ? "{\"ok\":true,\"tags\":[\"ab12cd34\",\"bad tag\"],\"sessions\":[\"10.1.2.3\",\"x; y\"],"
                            + "\"profiles\":[{\"name\":\"Public\",\"enabled\":false}]}"
                    : "{\"ok\":true,\"message\":\"created\",\"found\":true}");
        }, null);
        FwRule r = new FwRule(UUID.randomUUID(), "ab12cd34", "BLOCK", "INBOUND", "203.0.113.0/24", null, "ICMP", null, null, "ENFORCE", null);
        b.apply(r);
        assertEquals("apply", envs.get(0).get("NETMON_FW_OP"));
        assertEquals("ICMPv4", envs.get(0).get("NETMON_FW_PROTOCOL"));
        assertEquals("Block", envs.get(0).get("NETMON_FW_ACTION"));
        assertEquals(Set.of("ab12cd34"), b.tags());
        assertEquals(List.of("10.1.2.3"), b.managementSessions());
        assertEquals(List.of("Public"), b.offProfiles());
        assertEquals("New-NetFirewallRule -Name netmon-ab12cd34 -Group NetMon -Direction Inbound -Action Block -RemoteAddress 203.0.113.0/24 -Protocol ICMPv4",
                b.describe(r));
        String script = WindowsFirewallBackend.load();
        assertTrue(script.contains("New-NetFirewallRule @p -WhatIf"));
        assertFalse(script.contains("Invoke-Expression"));
    }

    @Test
    void readsEstablishedManagementSessionsFromProc() throws Exception {
        Path proc = root.resolve("proc/net");
        Files.createDirectories(proc);
        // 10.0.0.5:22 <- 192.168.1.50:51000 ESTABLISHED; :443 connection ignored; LISTEN ignored
        Files.writeString(proc.resolve("tcp"), """
                  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
                   0: 0500000A:0016 3201A8C0:C738 01 00000000:00000000 00:00000000 00000000     0        0 1
                   1: 0500000A:01BB 0B01A8C0:C739 01 00000000:00000000 00:00000000 00000000     0        0 2
                   2: 00000000:0016 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 3
                """);
        Files.writeString(proc.resolve("tcp6"), """
                  sl  local_address                         remote_address                        st
                   0: 00000000000000000000000001000000:0016 B80D0120000000000000000002000000:D431 01
                   1: 0000000000000000FFFF00000500000A:0D3D 0000000000000000FFFF00000A01A8C0:D432 01
                """);
        assertEquals(List.of("192.168.1.50", "2001:db8::2", "192.168.1.10"),
                LocalSafety.linuxSessions(root.resolve("proc"), List.of(22, 3389)));
    }

    @Test
    void parsesOnlyStrictLiteralRules() throws Exception {
        assertEquals("2001:db8::/32", FwRule.cidr("2001:DB8::/32"));
        assertEquals("10.0.0.0/8", FwRule.cidr("10.0.0.0/8"));
        assertThrows(IllegalArgumentException.class, () -> FwRule.cidr("10.0.0.1/8"));
        assertThrows(IllegalArgumentException.class, () -> FwRule.cidr("300.0.0.0/8"));
        assertThrows(IllegalArgumentException.class, () -> FwRule.cidr("localhost/32"));
        assertThrows(IllegalArgumentException.class, () -> FwRule.port("22 drop"));
        assertTrue(Cidrs.overlaps("10.0.0.0/8", "10.9.0.0/16"));
        assertFalse(Cidrs.overlaps("10.0.0.0/8", "::/0"));
        assertTrue(Cidrs.overlaps("::/0", "::1/128"));
        Report.Firewall none = new Report.Firewall(false, false, null, "x", List.of(), null, null);
        assertNull(none.ruleTags());
    }
}
