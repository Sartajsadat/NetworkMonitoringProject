package gov.mot.netmon.agent;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.CollectorRunner;
import gov.mot.netmon.agent.collect.CommandRunner;
import gov.mot.netmon.agent.config.AgentSettings;
import gov.mot.netmon.agent.linux.CpuCollector;
import gov.mot.netmon.agent.linux.DiskCollector;
import gov.mot.netmon.agent.linux.DiskIoCollector;
import gov.mot.netmon.agent.linux.HostCollector;
import gov.mot.netmon.agent.linux.MemoryCollector;
import gov.mot.netmon.agent.linux.NetworkCollector;
import gov.mot.netmon.agent.linux.ProcessCollector;
import gov.mot.netmon.agent.linux.ServiceCollector;
import gov.mot.netmon.agent.model.Report;

/** Linux collectors in the order they must run (host first: others need boot time and memory size). */
public final class LinuxPlatform implements Platform {

    @Override
    public gov.mot.netmon.agent.firewall.FirewallBackend firewall(AgentSettings s) {
        return gov.mot.netmon.agent.firewall.FirewallDetector.linux(s.firewall(), s.etcRoot(), s.stateDir().resolve("firewall-tmp"));
    }

    @Override
    public List<String> managementSessions(AgentSettings s) {
        return gov.mot.netmon.agent.firewall.LocalSafety.linuxSessions(s.procRoot(), s.firewall().managementPorts());
    }

    @Override
    public String name() {
        return "LINUX";
    }

    @Override
    public List<Collector> collectors(AgentSettings s, Supplier<List<String>> monitoredServices) {
        return List.of(
                new HostCollector(s.procRoot(), s.etcRoot()),
                new CpuCollector(s.procRoot()),
                new MemoryCollector(s.procRoot()),
                new DiskCollector(s.procRoot()),
                new DiskIoCollector(s.procRoot(), s.sysRoot()),
                new NetworkCollector(s.sysRoot()),
                new gov.mot.netmon.agent.linux.NeighborCollector(s.procRoot()),
                new ServiceCollector(CommandRunner.fixed(s.systemctl(), 5), monitoredServices),
                new ProcessCollector(s.procRoot(), s.etcRoot(), s.clockTicksPerSecond()));
    }

    @Override
    public Report.Host hostInfo(AgentSettings s) {
        return new CollectorRunner(List.of(new HostCollector(s.procRoot(), s.etcRoot()))).run(Instant.now()).host;
    }

    @Override
    public String machineIdHash(AgentSettings s) {
        try {
            return Platform.hash(Files.readString(s.etcRoot().resolve("machine-id")).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
