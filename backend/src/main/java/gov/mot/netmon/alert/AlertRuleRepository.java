package gov.mot.netmon.alert;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRuleRepository extends JpaRepository<AlertRule, UUID> {

    List<AlertRule> findByEnabledTrue();

    List<AlertRule> findAllByOrderByNameAsc();
}
