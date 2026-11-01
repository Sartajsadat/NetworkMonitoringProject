package gov.mot.netmon.agent;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<Agent, UUID> {

    Optional<Agent> findByServerId(UUID serverId);
}
