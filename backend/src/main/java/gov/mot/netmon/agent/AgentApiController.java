package gov.mot.netmon.agent;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import gov.mot.netmon.agent.AgentProtocol.EnrollRequest;
import gov.mot.netmon.agent.AgentProtocol.EnrollResponse;
import gov.mot.netmon.agent.AgentProtocol.Report;
import gov.mot.netmon.agent.AgentProtocol.ReportResponse;
import gov.mot.netmon.agent.AgentProtocol.RotateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Endpoints called by monitoring agents (outbound from the monitored server). */
@RestController
@RequestMapping("/api/v1/agent")
@Tag(name = "Agent protocol", description = "Called by monitoring agents, authenticated with 'Authorization: Agent <id>:<secret>'")
public class AgentApiController {

    private final AgentEnrollmentService enrollment;
    private final AgentReportService reports;

    public AgentApiController(AgentEnrollmentService enrollment, AgentReportService reports) {
        this.enrollment = enrollment;
        this.reports = reports;
    }

    @PostMapping("/enroll")
    @Operation(summary = "Exchange a one-time enrollment token for an agent credential")
    public EnrollResponse enroll(@Valid @RequestBody EnrollRequest req) {
        return enrollment.enroll(req);
    }

    @PostMapping("/report")
    @Operation(summary = "Heartbeat + metrics report; the response carries the agent's configuration")
    public ReportResponse report(@AuthenticationPrincipal AgentPrincipal agent, @Valid @RequestBody Report report) {
        return reports.ingest(agent, report);
    }

    @PostMapping("/credentials/rotate")
    @Operation(summary = "Rotate the agent secret (the old one stays valid for a short grace period)")
    public RotateResponse rotate(@AuthenticationPrincipal AgentPrincipal agent) {
        return enrollment.rotate(agent);
    }
}
