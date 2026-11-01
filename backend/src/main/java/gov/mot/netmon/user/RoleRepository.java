package gov.mot.netmon.user;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Short> {

    List<Role> findByCodeIn(Collection<RoleCode> codes);
}
