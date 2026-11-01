package gov.mot.netmon.server;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "server_ip_addresses")
public class ServerIpAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_id")
    private Server server;

    @Column(name = "ip_address", nullable = false)
    private String ipAddress;

    @Column(name = "ip_version", nullable = false)
    private short ipVersion;

    private String label;

    protected ServerIpAddress() {
    }

    ServerIpAddress(Server server, String ipAddress, int ipVersion, String label) {
        this.server = server;
        this.ipAddress = ipAddress;
        this.ipVersion = (short) ipVersion;
        this.label = label;
    }

    public Long getId() { return id; }
    public String getIpAddress() { return ipAddress; }
    public int getIpVersion() { return ipVersion; }
    public String getLabel() { return label; }

    void setLabel(String label) {
        this.label = label;
    }
}
