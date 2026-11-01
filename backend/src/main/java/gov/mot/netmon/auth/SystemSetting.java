package gov.mot.netmon.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "system_settings")
public class SystemSetting {

    @Id
    private String key;

    @Column(nullable = false)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SystemSetting() {
    }

    public SystemSetting(String key, String value) {
        this.key = key;
        this.value = value;
        this.updatedAt = Instant.now();
    }

    public String getKey() { return key; }
    public String getValue() { return value; }
}
