package gov.mot.netmon.server;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import gov.mot.netmon.common.IpAddresses;
import gov.mot.netmon.server.ServerEnums.AgentHealth;
import gov.mot.netmon.server.ServerEnums.ConnectionStatus;
import gov.mot.netmon.server.ServerEnums.Environment;
import gov.mot.netmon.server.ServerEnums.MonitoringStatus;
import gov.mot.netmon.server.ServerEnums.OsFamily;

@Entity
@Table(name = "servers")
public class Server {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String hostname;

    @Enumerated(EnumType.STRING)
    @Column(name = "os_family", nullable = false)
    private OsFamily osFamily;

    @Column(name = "os_version")
    private String osVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Environment environment;

    @Column(nullable = false)
    private String organization;

    private String purpose;

    private String location;

    @Enumerated(EnumType.STRING)
    @Column(name = "monitoring_status", nullable = false)
    private MonitoringStatus monitoringStatus;

    @Column(name = "agent_version")
    private String agentVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent_health", nullable = false)
    private AgentHealth agentHealth = AgentHealth.NO_AGENT;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @Column(name = "updated_by")
    private String updatedBy;

    @OneToMany(mappedBy = "server", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<ServerIpAddress> ipAddresses = new ArrayList<>();

    @ManyToMany
    @JoinTable(name = "server_group_members",
            joinColumns = @JoinColumn(name = "server_id"),
            inverseJoinColumns = @JoinColumn(name = "group_id"))
    private Set<ServerGroup> groups = new HashSet<>();

    protected Server() {
    }

    public Server(String createdBy) {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.createdBy = createdBy;
        this.monitoringStatus = MonitoringStatus.PENDING_AGENT;
    }

    /** Derived from the last agent heartbeat; with no agent it is NO_AGENT, never a guess. */
    public ConnectionStatus connectionStatus(Instant now, Duration onlineThreshold) {
        if (lastSeenAt == null || agentHealth == AgentHealth.NO_AGENT) {
            return ConnectionStatus.NO_AGENT;
        }
        return lastSeenAt.isAfter(now.minus(onlineThreshold)) ? ConnectionStatus.ONLINE : ConnectionStatus.OFFLINE;
    }

    public void applyDetails(String name, String hostname, OsFamily osFamily, String osVersion,
            Environment environment, String organization, String purpose, String location,
            MonitoringStatus monitoringStatus, String notes, String updatedBy) {
        this.name = name;
        this.hostname = hostname;
        this.osFamily = osFamily;
        this.osVersion = osVersion;
        this.environment = environment;
        this.organization = organization;
        this.purpose = purpose;
        this.location = location;
        this.monitoringStatus = monitoringStatus;
        this.notes = notes;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public record IpEntry(String address, String label) {
    }

    /**
     * Replaces the IP list. Unchanged addresses keep their row (label updated in place) because
     * Hibernate flushes inserts before deletes, so delete+insert of the same address would
     * violate the (server_id, ip_address) unique constraint.
     */
    public void replaceIpAddresses(List<IpEntry> entries) {
        java.util.Map<String, IpAddresses.ParsedIp> parsed = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> labels = new java.util.HashMap<>();
        for (IpEntry e : entries) {
            IpAddresses.ParsedIp p = IpAddresses.parse(e.address());
            if (parsed.putIfAbsent(p.canonical(), p) != null) {
                throw new IllegalArgumentException("IP address " + p.canonical() + " is listed twice");
            }
            labels.put(p.canonical(), e.label() == null || e.label().isBlank() ? null : e.label().trim());
        }
        ipAddresses.removeIf(existing -> !parsed.containsKey(existing.getIpAddress()));
        for (IpAddresses.ParsedIp p : parsed.values()) {
            ipAddresses.stream().filter(x -> x.getIpAddress().equals(p.canonical())).findFirst().ifPresentOrElse(
                    x -> x.setLabel(labels.get(p.canonical())),
                    () -> ipAddresses.add(new ServerIpAddress(this, p.canonical(), p.version(),
                            labels.get(p.canonical()))));
        }
    }

    public void replaceGroups(Set<ServerGroup> newGroups) {
        groups.clear();
        groups.addAll(newGroups);
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getHostname() { return hostname; }
    public OsFamily getOsFamily() { return osFamily; }
    public String getOsVersion() { return osVersion; }
    public Environment getEnvironment() { return environment; }
    public String getOrganization() { return organization; }
    public String getPurpose() { return purpose; }
    public String getLocation() { return location; }
    public MonitoringStatus getMonitoringStatus() { return monitoringStatus; }
    public String getAgentVersion() { return agentVersion; }
    public AgentHealth getAgentHealth() { return agentHealth; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public String getNotes() { return notes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getCreatedBy() { return createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public List<ServerIpAddress> getIpAddresses() { return ipAddresses; }
    public Set<ServerGroup> getGroups() { return groups; }
}
