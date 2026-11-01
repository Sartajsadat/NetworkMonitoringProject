package gov.mot.netmon.alert;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface AlertRepository extends JpaRepository<Alert, UUID>, JpaSpecificationExecutor<Alert> {

    List<Alert> findByRuleIdAndStatusIn(UUID ruleId, Collection<AlertStatus> statuses);

    /** Latest alert for a key that was resolved by itself or by a person (rule changes do not start a cooldown). */
    @Query("select max(a.resolvedAt) from Alert a where a.dedupKey = ?1 and a.status = 'RESOLVED' "
            + "and a.resolutionType in ('AUTO', 'MANUAL')")
    Optional<Instant> lastResolvedAt(String dedupKey);

    List<Alert> findByStatusIn(Collection<AlertStatus> statuses);
}
