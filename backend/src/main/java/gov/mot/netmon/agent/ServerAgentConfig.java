package gov.mot.netmon.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Per-server agent settings, delivered to the agent in every report response. */
@Entity
@Table(name = "server_agent_configs")
public class ServerAgentConfig {

    @Id
    @Column(name = "server_id")
    private UUID serverId;

    @Column(name = "report_interval_seconds", nullable = false)
    private int reportIntervalSeconds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "monitored_services", nullable = false)
    private List<String> monitoredServices = new ArrayList<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected ServerAgentConfig() {
    }

    public ServerAgentConfig(UUID serverId, int reportIntervalSeconds) {
        this.serverId = serverId;
        this.reportIntervalSeconds = reportIntervalSeconds;
        this.updatedAt = Instant.now();
    }

    public void update(int interval, List<String> services, String by) {
        this.reportIntervalSeconds = interval;
        this.monitoredServices = new ArrayList<>(services);
        this.updatedAt = Instant.now();
        this.updatedBy = by;
    }

    public UUID getServerId() { return serverId; }
    public int getReportIntervalSeconds() { return reportIntervalSeconds; }
    public List<String> getMonitoredServices() { return monitoredServices; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
