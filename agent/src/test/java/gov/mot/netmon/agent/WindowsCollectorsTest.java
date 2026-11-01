package gov.mot.netmon.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.agent.collect.CollectorRunner;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.model.Json;
import gov.mot.netmon.agent.model.Report;
import gov.mot.netmon.agent.windows.PowerShellQuery;
import gov.mot.netmon.agent.windows.WindowsQuery;

/**
 * Windows collectors fed with recorded PowerShell output (two cycles 10 s apart). The real
 * PowerShell/CIM calls can only run on Windows; this verifies everything the agent does with the data.
 */
class WindowsCollectorsTest {

    final Instant t0 = Instant.parse("2026-11-01T10:00:00Z");
    final Instant t1 = t0.plusSeconds(10);

    static JsonNode fixture(String name) throws Exception {
        try (InputStream in = WindowsCollectorsTest.class.getResourceAsStream("/windows/" + name)) {
            return Json.MAPPER.readTree(in);
        }
    }

    /** Returns the fixtures in order and records which services were requested. */
    static class FixtureQuery implements WindowsQuery {
        final List<JsonNode> cycles;
        final List<List<String>> requested = new ArrayList<>();
        int i;

        FixtureQuery(JsonNode... cycles) {
            this.cycles = List.of(cycles);
        }

        @Override
        public JsonNode run(List<String> services) {
            requested.add(services);
            return cycles.get(Math.min(i++, cycles.size() - 1));
        }
    }

    private ReportBuilder[] twoCycles() throws Exception {
        FixtureQuery q = new FixtureQuery(fixture("cycle1.json"), fixture("cycle2.json"));
        CollectorRunner runner = new CollectorRunner(WindowsPlatform.collectors(q, () -> List.of("W3SVC", "Spooler", "MissingSvc")));
        return new ReportBuilder[] {runner.run(t0), runner.run(t1)};
    }

    @Test
    void hostDetails() throws Exception {
        Report.Host h = twoCycles()[0].host;
        assertEquals("APP-WIN-01", h.hostname());
        assertEquals("WINDOWS", h.platform());
        assertEquals("Microsoft Windows Server 2022 Standard", h.osName());
        assertEquals("10.0.20348", h.osVersion());
        assertEquals("64-bit", h.architecture());
        assertEquals(2, h.cpuCount());
        assertEquals(24 * 3600L, h.uptimeSeconds());
    }

    @Test
    void cpuFromInverseTimerAndNoLoadAverage() throws Exception {
        ReportBuilder[] r = twoCycles();
        assertNull(r[0].cpu.usagePercent(), "first sample has no rate");
        // idle +50,000,000 / +100,000,000 ticks => 50 % idle => 50 % busy on core 0; core 1: 90 % idle => 10 % busy
        assertEquals(30.0, r[1].cpu.usagePercent(), 0.001);
        assertEquals(List.of(50.0, 10.0), r[1].cpu.perCore());
        assertEquals(5, r[1].cpu.runQueue(), "processor queue length; single object accepted as array");
        assertNull(r[1].cpu.load1(), "Windows has no load average: left empty, not zero");
        assertNull(r[1].cpu.iowaitPercent());
    }

    @Test
    void memoryAndPageFile() throws Exception {
        ReportBuilder[] r = twoCycles();
        Report.Memory m = r[0].memory;
        assertEquals(16L * 1024 * 1024 * 1024, m.totalBytes());
        assertEquals(75.0, m.usedPercent());
        assertEquals(4096L * 1024 * 1024, m.swapTotalBytes());
        assertEquals(25.0, m.swapUsedPercent());
        // cycle 2: page file access denied -> memory still reported, page file values unknown (not zero %)
        assertEquals(75.0, r[1].memory.usedPercent());
        assertNull(r[1].memory.swapUsedPercent());
    }

    @Test
    void disksSkipUnformattedVolumes() throws Exception {
        List<Report.Disk> d = twoCycles()[0].disks;
        assertEquals(1, d.size());
        assertEquals("C:\\", d.get(0).mount());
        assertEquals("NTFS", d.get(0).fsType());
        assertEquals(70.0, d.get(0).usedPercent());
    }

    @Test
    void diskIoRatesAndBusyTime() throws Exception {
        Report.DiskIo io = twoCycles()[1].diskIo.get(0);
        assertEquals("0_C:", io.device());
        assertEquals(1_000_000.0, io.readBytesPerSec(), 0.1);
        assertEquals(0.0, io.writeBytesPerSec(), 0.1);
        assertEquals(10.0, io.readOpsPerSec(), 0.001);
        assertEquals(25.0, io.busyPercent(), 0.001); // 75 % idle
    }

    @Test
    void networkCountersRatesAddressesAndState() throws Exception {
        ReportBuilder[] r = twoCycles();
        Map<String, Report.NetworkInterface> first = r[0].network.stream().collect(Collectors.toMap(Report.NetworkInterface::name, n -> n));
        assertEquals("up", first.get("Ethernet0").operState());
        assertEquals("down", first.get("Ethernet1").operState());
        assertEquals("00:50:56:aa:bb:cc", first.get("Ethernet0").macAddress());
        assertEquals(10000L, first.get("Ethernet0").speedMbps());
        assertEquals(List.of("10.30.0.15/24", "fe80::1234/64"), first.get("Ethernet0").addresses());
        assertNull(first.get("Ethernet0").rxBitsPerSec());
        assertNull(first.get("Ethernet1").rxBytes(), "no statistics for this adapter: unknown, not zero");

        Report.NetworkInterface eth = r[1].network.get(0);
        assertEquals(12_500_000 * 8 / 10.0, eth.rxBitsPerSec(), 0.1);
        assertEquals(100.0, eth.rxPacketsPerSec(), 0.001);
        assertEquals(1.0, eth.dropsPerSec(), 0.001);
    }

    @Test
    void servicesAreCaseInsensitiveAndMissingOnesReported() throws Exception {
        ReportBuilder[] r = twoCycles();
        assertEquals(List.of("RUNNING", "STOPPED", "NOT_FOUND"), r[0].services.stream().map(Report.Service::status).toList());
        assertEquals("Auto", r[0].services.get(0).loadState());
        assertEquals("STOPPING", r[1].services.get(0).status());
        assertEquals("NOT_FOUND", r[1].services.get(1).status());
    }

    @Test
    void processesTopByCpuAndMemoryWithoutOwnerOrIdle() throws Exception {
        ReportBuilder[] r = twoCycles();
        List<Report.ProcessInfo> p = r[1].processes;
        assertEquals("w3wp", p.get(0).name());
        assertEquals(50.0, p.get(0).cpuPercent(), 0.001); // 5 CPU-seconds in 10 s
        assertNull(p.get(0).user(), "owner needs administrator rights, so it is not collected");
        assertTrue(r[0].processes.stream().noneMatch(x -> x.pid() == 0), "the Idle pseudo-process is skipped");
        assertEquals(Instant.parse("2026-11-01T08:00:00Z"), p.get(0).startedAt());
    }

    @Test
    void sectionErrorsBecomeHonestCollectorStatuses() throws Exception {
        JsonNode broken = Json.MAPPER.readTree("""
                {"errors":{"cpu":"Invalid class \\"Win32_PerfRawData_PerfOS_Processor\\"",
                           "disks":"Access is denied.","adapters":"The term 'Get-NetAdapter' is not recognized"},
                 "os":[{"CSName":"X","TotalVisibleMemorySize":100,"FreePhysicalMemory":50}]}""");
        ReportBuilder b = new CollectorRunner(WindowsPlatform.collectors(new FixtureQuery(broken), List::of)).run(t0);
        Map<String, String> status = b.collectors.stream().collect(Collectors.toMap(Report.CollectorStatus::name, Report.CollectorStatus::status));
        assertEquals("OK", status.get("windows-query"));
        assertEquals("OK", status.get("host"));
        assertEquals("UNSUPPORTED", status.get("cpu"));
        assertEquals("PERMISSION_DENIED", status.get("disks"));
        assertEquals("UNSUPPORTED", status.get("network"));
        assertNull(b.cpu);
        assertNotNull(b.memory);
    }

    @Test
    void whenPowerShellCannotRunEveryCollectorSaysUnavailable() {
        WindowsQuery failing = s -> { throw new java.nio.file.NoSuchFileException("powershell.exe not found"); };
        ReportBuilder b = new CollectorRunner(WindowsPlatform.collectors(failing, List::of)).run(t0);
        assertTrue(b.collectors.stream().allMatch(c -> c.status().equals("UNAVAILABLE")), b.collectors.toString());
        assertNull(b.host);
    }

    @Test
    void scriptIsBundledConstantAndOnlyValidatedServiceNamesArePassed() throws Exception {
        FixtureQuery q = new FixtureQuery(fixture("cycle1.json"));
        new CollectorRunner(WindowsPlatform.collectors(q, () -> List.of("W3SVC"))).run(t0);
        assertEquals(List.of("W3SVC"), q.requested.get(0));
        // Validation applied before the environment variable is built:
        var safe = (List<?>) invokeSafe(List.of("W3SVC", "MSSQL$SQLEXPRESS", "a,b", "x'; Remove-Item C:\\", "with space"));
        assertEquals(List.of("W3SVC", "MSSQL$SQLEXPRESS"), safe);

        PowerShellQuery ps = new PowerShellQuery(java.nio.file.Path.of("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"));
        List<String> cmd = (List<String>) invoke(ps, "command");
        assertEquals("-EncodedCommand", cmd.get(cmd.size() - 2));
        String script = new String(Base64.getDecoder().decode(cmd.get(cmd.size() - 1)), StandardCharsets.UTF_16LE);
        assertTrue(script.contains("Win32_PerfRawData_PerfOS_Processor"));
        assertFalse(script.toLowerCase().contains("commandline"), "command lines are never read");
        assertFalse(script.matches("(?is).*\\b(stop-service|start-service|restart-service|remove-item|set-|invoke-expression)\\b.*"),
                "the script is read-only");
    }

    @Test
    void parsesOutputWithLeadingNoise() throws Exception {
        JsonNode n = (JsonNode) invokeStatic("parse", String.class, "WARNING: something\r\n{\"errors\":{},\"os\":[]}");
        assertTrue(n.has("os"));
    }

    // --- small reflection helpers for package-private methods in another package ---
    private static Object invokeSafe(List<String> in) throws Exception {
        var m = PowerShellQuery.class.getDeclaredMethod("safeServices", List.class);
        m.setAccessible(true);
        return m.invoke(null, in);
    }

    private static Object invoke(Object target, String name) throws Exception {
        var m = target.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Object invokeStatic(String name, Class<?> type, Object arg) throws Exception {
        var m = PowerShellQuery.class.getDeclaredMethod(name, type);
        m.setAccessible(true);
        return m.invoke(null, arg);
    }
}
