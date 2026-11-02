package gov.mot.netmon.firewall;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FirewallIntegrationRepository extends JpaRepository<FirewallIntegration, UUID> {

    Optional<FirewallIntegration> findByServerId(UUID serverId);

    List<FirewallIntegration> findAllByOrderByNameAsc();

    boolean existsByName(String name);
}
