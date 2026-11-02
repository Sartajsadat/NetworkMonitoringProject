package gov.mot.netmon.capture;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTaskRepository extends JpaRepository<AgentTask, UUID> {

    List<AgentTask> findByServerIdAndStatusOrderByCreatedAt(UUID serverId, String status);

    List<AgentTask> findByCaptureIdAndStatus(UUID captureId, String status);

    List<AgentTask> findByFirewallRuleIdAndStatus(UUID firewallRuleId, String status);

    List<AgentTask> findByFirewallRuleIdOrderByCreatedAtDesc(UUID firewallRuleId);
}
