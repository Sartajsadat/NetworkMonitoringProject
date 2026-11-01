package gov.mot.netmon.agent;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ServerAgentConfigRepository extends JpaRepository<ServerAgentConfig, UUID> {
}
