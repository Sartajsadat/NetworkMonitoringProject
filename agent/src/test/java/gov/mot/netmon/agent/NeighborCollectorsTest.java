package gov.mot.netmon.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import gov.mot.netmon.agent.collect.CollectorRunner;
import gov.mot.netmon.agent.linux.NeighborCollector;
import gov.mot.netmon.agent.model.Report;
import gov.mot.netmon.agent.windows.WinNeighborCollector;
import gov.mot.netmon.agent.windows.WindowsQueryCollector;

class NeighborCollectorsTest {

    @TempDir
    Path root;

    @Test
    void readsCompleteEntriesFromProcNetArp() throws Exception {
        Path arp = Files.createDirectories(root.resolve("proc/net")).resolve("arp");
        Files.writeString(arp, """
                IP address       HW type     Flags       HW address            Mask     Device
                10.0.0.1         0x1         0x2         00:11:22:33:44:55     *        eth0
                10.0.0.7         0x1         0x0         00:00:00:00:00:00     *        eth0
                10.0.0.9         0x1         0x6         AA:BB:CC:DD:EE:FF     *        eth1
                """);
        var b = new CollectorRunner(List.of(new NeighborCollector(root.resolve("proc")))).run(Instant.now());
        assertEquals(List.of(new Report.Neighbor("10.0.0.1", "00:11:22:33:44:55", "eth0", "REACHABLE"),
                new Report.Neighbor("10.0.0.9", "aa:bb:cc:dd:ee:ff", "eth1", "PERMANENT")), b.neighbors);
    }

    @Test
    void readsWindowsNeighboursWithoutScopeIds() throws Exception {
        var query = new WindowsCollectorsTest.FixtureQuery(WindowsCollectorsTest.fixture("cycle1.json"));
        var b = new CollectorRunner(List.of(new WindowsQueryCollector(query, List::of), new WinNeighborCollector()))
                .run(Instant.now());
        assertEquals(2, b.neighbors.size());
        assertEquals(new Report.Neighbor("10.20.0.1", "00:15:5d:01:02:03", "Ethernet0", "REACHABLE"), b.neighbors.get(0));
        assertEquals("fe80::1", b.neighbors.get(1).ip());
    }
}
