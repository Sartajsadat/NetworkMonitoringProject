package gov.mot.netmon.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import gov.mot.netmon.agent.AgentProtocol;
import gov.mot.netmon.network.IpObservations.Observation;
import gov.mot.netmon.server.Server;

/** Addresses in an agent report: the server's own interface addresses and its neighbour table. */
@Component
public class ReportAddresses {

    /** Neighbour states that mean the address answered recently (Linux and Windows names). */
    static final Set<String> LIVE = Set.of("REACHABLE", "STALE", "DELAY", "PROBE", "PERMANENT", "COMPLETE");

    private final IpObservations observations;

    public ReportAddresses(IpObservations observations) {
        this.observations = observations;
    }

    public void record(Server server, AgentProtocol.Report r) {
        List<Observation> list = new ArrayList<>();
        String host = r.host() == null ? null : r.host().hostname();
        if (r.network() != null) {
            for (AgentProtocol.NetworkInterface nic : r.network()) {
                if (nic.addresses() == null) continue;
                for (String a : nic.addresses()) {
                    String ip = a.contains("/") ? a.substring(0, a.indexOf('/')) : a;
                    int pct = ip.indexOf('%'); // fe80::1%eth0
                    if (pct > 0) ip = ip.substring(0, pct);
                    list.add(new Observation(ip, IpSource.AGENT_INTERFACE, server.getId(), null, nic.macAddress(), host,
                            r.collectedAt()));
                }
            }
        }
        if (r.neighbors() != null) {
            for (AgentProtocol.Neighbor n : r.neighbors()) {
                if (n.state() != null && !LIVE.contains(n.state().toUpperCase(java.util.Locale.ROOT))) continue;
                list.add(new Observation(n.ip(), IpSource.NEIGHBOR, null, null, n.mac(), null, r.collectedAt()));
            }
        }
        observations.observeAll(list);
    }
}
