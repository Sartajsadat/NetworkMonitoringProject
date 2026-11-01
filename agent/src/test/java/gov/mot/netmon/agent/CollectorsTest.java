package gov.mot.netmon.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import gov.mot.netmon.agent.collect.CollectorRunner;
import gov.mot.netmon.agent.collect.CommandRunner;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.linux.CpuCollector;
import gov.mot.netmon.agent.linux.DiskCollector;
import gov.mot.netmon.agent.linux.DiskIoCollector;
import gov.mot.netmon.agent.linux.HostCollector;
import gov.mot.netmon.agent.linux.MemoryCollector;
import gov.mot.netmon.agent.linux.NetworkCollector;
import gov.mot.netmon.agent.linux.ProcessCollector;
import gov.mot.netmon.agent.linux.ServiceCollector;
import gov.mot.netmon.agent.model.Report;

class CollectorsTest {

    @TempDir
    Path root;
    FakeHost host;
    final Instant t0 = Instant.parse("2026-11-01T10:00:00Z");
    final Instant t1 = t0.plusSeconds(10);

    @BeforeEach
    void setUp() throws Exception {
        host = new FakeHost(root);
    }

    @Test
    void hostInfoAndBootTime() throws Exception {
        ReportBuilder b = new ReportBuilder();
        new HostCollector(host.proc, host.etc).collect(b, t0);
        assertEquals("web-01", b.host.hostname());
        assertEquals("Ubuntu", b.host.osName());
        assertEquals("24.04", b.host.osVersion());
        assertEquals("6.8.0-45-generic", b.host.kernel());
        assertEquals(2, b.host.cpuCount());
        assertEquals(1000L, b.host.uptimeSeconds());
        assertEquals(t0.minusMillis(1000500), b.bootTime);
    }

    @Test
    void cpuUsageNeedsTwoSamples() throws Exception {
        CpuCollector cpu = new CpuCollector(host.proc);
        ReportBuilder first = new ReportBuilder();
        cpu.collect(first, t0);
        assertNull(first.cpu.usagePercent(), "no rate on the first sample");
        assertEquals(0.5, first.cpu.load1());
        assertEquals(3, first.cpu.runQueue());

        // +100 user, +100 system, +700 idle, +100 iowait => busy 200 of 1000 = 20%, iowait 10%
        host.cpuStat(1100, 0, 600, 8700, 600);
        ReportBuilder second = new ReportBuilder();
        cpu.collect(second, t1);
        assertEquals(20.0, second.cpu.usagePercent(), 0.001);
        assertEquals(10.0, second.cpu.iowaitPercent(), 0.001);
        assertEquals(List.of(20.0, 20.0), second.cpu.perCore());
    }

    @Test
    void memoryUsesMemAvailable() throws Exception {
        ReportBuilder b = new ReportBuilder();
        new MemoryCollector(host.proc).collect(b, t0);
        assertEquals(8000000L * 1024, b.memory.totalBytes());
        assertEquals(2000000L * 1024, b.memory.usedBytes());
        assertEquals(25.0, b.memory.usedPercent());
        assertEquals(25.0, b.memory.swapUsedPercent());
    }

    @Test
    void disksIncludeOnlyRealFilesystemsAndDecodeSpaces() throws Exception {
        FakeHost.write(host.proc.resolve("mounts"), """
                /dev/sda1 / ext4 rw 0 0
                proc /proc proc rw 0 0
                tmpfs /run tmpfs rw 0 0
                /dev/sdb1 /data\\040disk xfs rw 0 0
                /dev/loop0 /snap/core/1 squashfs ro 0 0
                """);
        DiskCollector.FsStats stats = mount -> new long[] {1000, 300, 400}; // total, usable, free
        ReportBuilder b = new ReportBuilder();
        new DiskCollector(host.proc, stats).collect(b, t0);
        assertEquals(2, b.disks.size());
        assertEquals("/", b.disks.get(0).mount());
        assertEquals("/data disk", b.disks.get(1).mount());
        assertEquals(600L, b.disks.get(0).usedBytes());
        assertEquals(66.67, b.disks.get(0).usedPercent(), 0.01); // used / (used + usable), like df
    }

    @Test
    void diskIoSkipsPartitionsAndComputesRates() throws Exception {
        Files.createDirectories(host.sys.resolve("block/sda"));
        String fmt = "   8       0 sda %d 0 %d 0 %d 0 %d 0 0 %d 0%n   8       1 sda1 5 0 5 0 5 0 5 0 0 5 0%n   7 0 loop0 1 0 1 0 1 0 1 0 0 1 0%n";
        FakeHost.write(host.proc.resolve("diskstats"), String.format(fmt, 100, 1000, 50, 2000, 1000));
        DiskIoCollector io = new DiskIoCollector(host.proc, host.sys);
        ReportBuilder first = new ReportBuilder();
        io.collect(first, t0);
        assertEquals(1, first.diskIo.size());
        assertNull(first.diskIo.get(0).readBytesPerSec());
        FakeHost.write(host.proc.resolve("diskstats"), String.format(fmt, 200, 21000, 150, 4000, 6000));
        ReportBuilder second = new ReportBuilder();
        io.collect(second, t1);
        Report.DiskIo d = second.diskIo.get(0);
        assertEquals(20000 * 512 / 10.0, d.readBytesPerSec(), 0.1);
        assertEquals(10.0, d.readOpsPerSec(), 0.001);
        assertEquals(50.0, d.busyPercent(), 0.001); // 5000 ms busy in 10 s
    }

    @Test
    void networkRatesAndCounterReset() throws Exception {
        host.nic("eth0", 1_000_000, 500_000, 0, "up");
        host.nic("lo", 100, 100, 0, "unknown");
        NetworkCollector net = new NetworkCollector(host.sys, iface -> List.of("10.0.0.5/24"));
        ReportBuilder first = new ReportBuilder();
        net.collect(first, t0);
        Report.NetworkInterface eth0 = first.network.stream().filter(n -> n.name().equals("eth0")).findFirst().orElseThrow();
        assertNull(eth0.rxBitsPerSec());
        assertEquals(1000L, eth0.speedMbps());
        assertEquals("up", eth0.operState());
        assertEquals(List.of("10.0.0.5/24"), eth0.addresses());

        host.nic("eth0", 2_250_000, 600_000, 5, "up");
        ReportBuilder second = new ReportBuilder();
        net.collect(second, t1);
        eth0 = second.network.stream().filter(n -> n.name().equals("eth0")).findFirst().orElseThrow();
        assertEquals(1_250_000 * 8 / 10.0, eth0.rxBitsPerSec(), 0.1);
        assertEquals(100_000 * 8 / 10.0, eth0.txBitsPerSec(), 0.1);
        assertEquals(0.5, eth0.errorsPerSec(), 0.001);

        host.nic("eth0", 10, 10, 0, "up"); // counter reset (driver reload / reboot)
        ReportBuilder third = new ReportBuilder();
        net.collect(third, t1.plusSeconds(10));
        eth0 = third.network.stream().filter(n -> n.name().equals("eth0")).findFirst().orElseThrow();
        assertNull(eth0.rxBitsPerSec(), "a reset counter must not produce a negative or huge rate");
    }

    @Test
    void processesParseNamesWithSpacesAndNeverCollectCommandLines() throws Exception {
        ReportBuilder hostInfo = new ReportBuilder();
        new HostCollector(host.proc, host.etc).collect(hostInfo, t0);
        new MemoryCollector(host.proc).collect(hostInfo, t0);
        host.process(1, "systemd", 10, 10, 100, 0, 10000);
        host.process(42, "my (weird) proc", 100, 0, 500, 33, 200000);
        FakeHost.write(host.proc.resolve("42/cmdline"), "app\u0000--password=secret\u0000");
        ProcessCollector pc = new ProcessCollector(host.proc, host.etc, 100);
        ReportBuilder first = new ReportBuilder();
        first.bootTime = hostInfo.bootTime;
        first.memoryTotalBytes = hostInfo.memoryTotalBytes;
        pc.collect(first, t0);
        host.process(42, "my (weird) proc", 600, 0, 500, 33, 200000); // +500 ticks in 10 s at 100 Hz = 50%
        ReportBuilder second = new ReportBuilder();
        second.bootTime = hostInfo.bootTime;
        second.memoryTotalBytes = hostInfo.memoryTotalBytes;
        pc.collect(second, t1);
        Report.ProcessInfo p = second.processes.get(0);
        assertEquals(42, p.pid());
        assertEquals("my (weird) proc", p.name());
        assertEquals("www-data", p.user());
        assertEquals(50.0, p.cpuPercent(), 0.001);
        assertEquals(200000L * 1024, p.memoryBytes());
        assertEquals(hostInfo.bootTime.plusSeconds(5), p.startedAt());
        String json = gov.mot.netmon.agent.model.Json.MAPPER.writeValueAsString(second.processes);
        assertTrue(!json.contains("password"), "command lines must never be reported");
    }

    @Test
    void servicesUseFixedSystemctlArgumentsAndMapStates() throws Exception {
        List<List<String>> calls = new ArrayList<>();
        CommandRunner fake = args -> {
            calls.add(args);
            return switch (args.get(1)) {
                case "nginx.service" -> new CommandRunner.Result(0,
                        "LoadState=loaded\nActiveState=active\nSubState=running\nStateChangeTimestampMonotonic=5000000\n");
                case "db.service" -> new CommandRunner.Result(0, "LoadState=loaded\nActiveState=failed\nSubState=failed\n");
                default -> new CommandRunner.Result(0, "LoadState=not-found\nActiveState=inactive\nSubState=dead\n");
            };
        };
        ReportBuilder b = new ReportBuilder();
        b.bootTime = t0;
        new ServiceCollector(fake, () -> List.of("nginx.service", "db.service", "missing.service", "x; rm -rf /"))
                .collect(b, t1);
        assertEquals(3, b.services.size(), "invalid unit names are never passed to systemctl");
        assertEquals("RUNNING", b.services.get(0).status());
        assertEquals(t0.plusSeconds(5), b.services.get(0).since());
        assertEquals("FAILED", b.services.get(1).status());
        assertEquals("NOT_FOUND", b.services.get(2).status());
        assertEquals(List.of("show", "nginx.service",
                "--property=LoadState,ActiveState,SubState,StateChangeTimestampMonotonic", "--no-pager"), calls.get(0));
    }

    @Test
    void failingCollectorIsReportedWithStatusAndOthersStillRun() {
        ReportBuilder b = new CollectorRunner(List.of(
                new CpuCollector(root.resolve("does-not-exist")),
                new MemoryCollector(host.proc),
                new gov.mot.netmon.agent.collect.Collector() {
                    public String name() { return "windows-only"; }
                    public void collect(ReportBuilder r, Instant now) { throw new UnsupportedOperationException("not on Linux"); }
                })).run(t0);
        assertEquals("UNAVAILABLE", b.collectors.get(0).status());
        assertEquals("OK", b.collectors.get(1).status());
        assertEquals("UNSUPPORTED", b.collectors.get(2).status());
        assertNull(b.cpu);
        assertNotNull(b.memory);
    }
}
