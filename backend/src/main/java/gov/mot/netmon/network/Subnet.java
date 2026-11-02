package gov.mot.netmon.network;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "subnets")
public class Subnet {

    @Id
    private UUID id;
    private String cidr;
    @Column(name = "ip_version")
    private int ipVersion;
    private int prefix;
    private String name;
    private String description;
    @Column(name = "vlan_id")
    private Integer vlanId;
    private String gateway;
    private String location;
    private String organization;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Column(name = "updated_by")
    private String updatedBy;

    protected Subnet() {
    }

    public Subnet(String by, Instant now) {
        this.id = UUID.randomUUID();
        this.createdAt = now;
        this.createdBy = by;
    }

    public void apply(String cidr, int version, int prefix, String name, String description, Integer vlanId, String gateway,
            String location, String organization, String by, Instant now) {
        this.cidr = cidr;
        this.ipVersion = version;
        this.prefix = prefix;
        this.name = name;
        this.description = description;
        this.vlanId = vlanId;
        this.gateway = gateway;
        this.location = location;
        this.organization = organization;
        this.updatedBy = by;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getCidr() { return cidr; }
    public int getIpVersion() { return ipVersion; }
    public int getPrefix() { return prefix; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Integer getVlanId() { return vlanId; }
    public String getGateway() { return gateway; }
    public String getLocation() { return location; }
    public String getOrganization() { return organization; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
