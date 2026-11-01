package gov.mot.netmon.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "roles")
public class Role {

    @Id
    private Short id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    private RoleCode code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    protected Role() {
    }

    public Short getId() { return id; }
    public RoleCode getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
}
