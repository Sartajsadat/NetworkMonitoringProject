package gov.mot.netmon.firewall;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FirewallRuleRepository extends JpaRepository<FirewallRule, UUID>, JpaSpecificationExecutor<FirewallRule> {

    List<FirewallRule> findByIntegrationIdAndStatusIn(UUID integrationId, Collection<String> statuses);

    List<FirewallRule> findByStatusInAndExpiresAtBefore(Collection<String> statuses, Instant cutoff);

    List<FirewallRule> findByStatus(String status);

    Optional<FirewallRule> findByTag(String tag);

    long countByIntegrationIdAndStatusIn(UUID integrationId, Collection<String> statuses);

    long countByApplicationIdAndStatusIn(UUID applicationId, Collection<String> statuses);

    long countByApplicationId(UUID applicationId);
}
