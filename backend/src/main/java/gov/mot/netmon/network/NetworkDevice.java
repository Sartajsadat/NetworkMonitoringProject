package gov.mot.netmon.network;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "network_devices")
public class NetworkDevice {

    public static final java.util.Set<String> TYPES = java.util.Set.of("ROUTER", "SWITCH", "FIREWALL", "LOAD_BALANCER",
            "WIRELESS", "SENSOR", "OTHER");

    @Id
    private UUID id;
    private String name;
    @Column(name = "device_type")
    private String deviceType;
    private String vendor;
    private String model;
    @Column(name = "management_ip")
    private String managementIp;
    private String location;
    private String description;
    @Column(name = "flow_export")
    private boolean flowExport;
    @Column(name = "exporter_ip")
    private String exporterIp;
    @Column(name = "last_flow_at", insertable = false, updatable = false)
    private Instant lastFlowAt;
    @Column(name = "flows_received", insertable = false, updatable = false)
    private long flowsReceived;
    @Column(name = "flows_dropped", insertable = false, updatable = false)
    private long flowsDropped;
    @Column(name = "last_flow_version", insertable = false, updatable = false)
    private Integer lastFlowVersion;
    @Column(name = "sampling_interval", insertable = false, updatable = false)
    private Integer samplingInterval;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Column(name = "updated_by")
    private String updatedBy;

    protected NetworkDevice() {
    }

    public NetworkDevice(String by, Instant now) {
        this.id = UUID.randomUUID();
        this.createdAt = now;
        this.createdBy = by;
    }

    public void apply(String name, String type, String vendor, String model, String managementIp, String location,
            String description, boolean flowExport, String exporterIp, String by, Instant now) {
        this.name = name;
        this.deviceType = type;
        this.vendor = vendor;
        this.model = model;
        this.managementIp = managementIp;
        this.location = location;
        this.description = description;
        this.flowExport = flowExport;
        this.exporterIp = exporterIp;
        this.updatedBy = by;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDeviceType() { return deviceType; }
    public String getVendor() { return vendor; }
    public String getModel() { return model; }
    public String getManagementIp() { return managementIp; }
    public String getLocation() { return location; }
    public String getDescription() { return description; }
    public boolean isFlowExport() { return flowExport; }
    public String getExporterIp() { return exporterIp; }
    public Instant getLastFlowAt() { return lastFlowAt; }
    public long getFlowsReceived() { return flowsReceived; }
    public long getFlowsDropped() { return flowsDropped; }
    public Integer getLastFlowVersion() { return lastFlowVersion; }
    public Integer getSamplingInterval() { return samplingInterval; }
    public Instant getUpdatedAt() { return updatedAt; }
}
