package gov.mot.netmon.agent.collect;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import gov.mot.netmon.agent.model.Report;

/** Mutable holder filled by collectors, turned into an immutable {@link Report}. */
public class ReportBuilder {

    public Report.Host host;
    public Report.Cpu cpu;
    public Report.Memory memory;
    public List<Report.Disk> disks;
    public List<Report.DiskIo> diskIo;
    public List<Report.NetworkInterface> network;
    public List<Report.Service> services;
    public List<Report.ProcessInfo> processes;
    public final List<Report.CollectorStatus> collectors = new ArrayList<>();
    /** Host-level facts other collectors may need (e.g. boot time for process start times). */
    public Instant bootTime;
    public Long memoryTotalBytes;

    public Report build(String agentVersion, Instant collectedAt, int intervalSeconds) {
        return new Report(agentVersion, collectedAt, intervalSeconds, host, cpu, memory, disks, diskIo, network, services,
                processes, List.copyOf(collectors));
    }
}
