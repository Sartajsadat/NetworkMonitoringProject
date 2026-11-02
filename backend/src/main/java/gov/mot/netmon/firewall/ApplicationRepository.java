package gov.mot.netmon.firewall;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {

    List<Application> findAllByOrderByNameAsc();

    List<Application> findByServerIdOrderByNameAsc(UUID serverId);

    boolean existsByServerIdAndName(UUID serverId, String name);

    boolean existsByServerIdAndIdentityKindAndIdentityValue(UUID serverId, String kind, String value);
}
