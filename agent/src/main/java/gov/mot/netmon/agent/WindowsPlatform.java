package gov.mot.netmon.agent;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.CollectorRunner;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.config.AgentSettings;
import gov.mot.netmon.agent.model.Report;
import gov.mot.netmon.agent.windows.PowerShellQuery;
import gov.mot.netmon.agent.windows.WinCpuCollector;
import gov.mot.netmon.agent.windows.WinDiskCollector;
import gov.mot.netmon.agent.windows.WinDiskIoCollector;
import gov.mot.netmon.agent.windows.WinHostCollector;
import gov.mot.netmon.agent.windows.WinMemoryCollector;
import gov.mot.netmon.agent.windows.WinNetworkCollector;
import gov.mot.netmon.agent.windows.WinProcessCollector;
import gov.mot.netmon.agent.windows.WinServiceCollector;
import gov.mot.netmon.agent.windows.WindowsQuery;
import gov.mot.netmon.agent.windows.WindowsQueryCollector;

/**
 * Windows: one read-only PowerShell/CIM query per cycle (performance counters, Win32_* classes,
 * NetAdapter cmdlets), interpreted by separate collectors so each can report its own status.
 */
public final class WindowsPlatform implements Platform {

    @Override
    public gov.mot.netmon.agent.firewall.FirewallBackend firewall(AgentSettings s) {
        return gov.mot.netmon.agent.firewall.FirewallDetector.windows(s.powershell());
    }

    @Override
    public String name() {
        return "WINDOWS";
    }

    @Override
    public List<Collector> collectors(AgentSettings s, Supplier<List<String>> monitoredServices) {
        return collectors(new PowerShellQuery(s.powershell()), monitoredServices);
    }

    /** Package-visible for tests with a fixture query. */
    static List<Collector> collectors(WindowsQuery query, Supplier<List<String>> monitoredServices) {
        return List.of(
                new WindowsQueryCollector(query, monitoredServices),
                new WinHostCollector(),
                new WinCpuCollector(),
                new WinMemoryCollector(),
                new WinDiskCollector(),
                new WinDiskIoCollector(),
                new WinNetworkCollector(),
                new gov.mot.netmon.agent.windows.WinNeighborCollector(),
                new WinServiceCollector(monitoredServices),
                new WinProcessCollector());
    }

    @Override
    public Report.Host hostInfo(AgentSettings s) throws Exception {
        ReportBuilder b = new CollectorRunner(List.of(new WindowsQueryCollector(new PowerShellQuery(s.powershell()), List::of),
                new WinHostCollector())).run(Instant.now());
        if (b.host == null) {
            throw new IllegalStateException("Could not read Windows host information: " + b.collectors);
        }
        return b.host;
    }

    @Override
    public String machineIdHash(AgentSettings s) {
        try {
            var data = new PowerShellQuery(s.powershell()).run(List.of());
            var rows = data.path("machine");
            var row = rows.isArray() ? rows.path(0) : rows;
            String guid = row.path("MachineGuid").asText(null);
            return guid == null || guid.isBlank() ? null : Platform.hash(guid);
        } catch (Exception e) {
            return null;
        }
    }
}
