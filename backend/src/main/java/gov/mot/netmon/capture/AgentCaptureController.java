package gov.mot.netmon.capture;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import gov.mot.netmon.agent.AgentPrincipal;
import gov.mot.netmon.capture.CaptureDtos.AgentStatusUpdate;
import gov.mot.netmon.capture.CaptureDtos.AgentTasks;
import gov.mot.netmon.capture.CaptureDtos.SegmentAck;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Capture endpoints for agents (agent credential required; see the agent security chain). */
@RestController
@RequestMapping("/api/v1/agent")
@Tag(name = "Agent protocol")
public class AgentCaptureController {

    private final CaptureAgentService service;

    public AgentCaptureController(CaptureAgentService service) {
        this.service = service;
    }

    @PostMapping("/tasks")
    @Operation(summary = "Typed tasks waiting for this agent (captures and firewall rules)")
    public AgentTasks tasks(@AuthenticationPrincipal AgentPrincipal agent) {
        return service.pollTasks(agent);
    }

    @PostMapping("/captures/{id}/status")
    public SegmentAck status(@AuthenticationPrincipal AgentPrincipal agent, @PathVariable UUID id,
            @Valid @RequestBody AgentStatusUpdate update) {
        return service.status(agent, id, update);
    }

    @PutMapping("/captures/{id}/segments/{seq}")
    @Operation(summary = "Upload one PCAPNG piece of a running capture (raw body)")
    public SegmentAck upload(@AuthenticationPrincipal AgentPrincipal agent, @PathVariable UUID id, @PathVariable int seq,
            HttpServletRequest request) throws IOException {
        return service.upload(agent, id, seq, request.getInputStream());
    }
}
