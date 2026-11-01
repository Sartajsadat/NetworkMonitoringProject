package gov.mot.netmon.agent;

import java.util.UUID;

/** Authenticated monitoring agent (placed in the SecurityContext for /api/v1/agent/** calls). */
public record AgentPrincipal(UUID agentId, UUID serverId) {
}
