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
    /** Raw platform data shared by several collectors in one cycle (Windows: one PowerShell query). */
    public com.fasterxml.jackson.databind.JsonNode platformData;

    /** Capture capability, set by the runner (not by a collector). */
    public Report.Capture capture;
    public List<Report.Neighbor> neighbors;
    public Report.Firewall firewall;

    public Report build(String agentVersion, Instant collectedAt, int intervalSeconds) {
        return new Report(agentVersion, collectedAt, intervalSeconds, host, cpu, memory, disks, diskIo, network, services,
                processes, List.copyOf(collectors), capture, neighbors, firewall);
    }
}
