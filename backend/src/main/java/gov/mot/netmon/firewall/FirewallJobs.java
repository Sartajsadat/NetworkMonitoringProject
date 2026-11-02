package gov.mot.netmon.firewall;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.capture.AgentTask;
import gov.mot.netmon.capture.AgentTaskRepository;

/**
 * Expiry and stuck-task handling. Expired rules are removed from the target (the agent also removes
 * them by itself if the platform is unreachable). An approved rule the agent never confirms is
 * marked FAILED - never assumed applied.
 */
@Component
public class FirewallJobs {

    private static final Logger log = LoggerFactory.getLogger(FirewallJobs.class);

    private final FirewallRuleRepository rules;
    private final FirewallIntegrationRepository integrations;
    private final FirewallRuleService service;
    private final FirewallService firewalls;
    private final AgentTaskRepository tasks;
    private final Clock clock;

    public FirewallJobs(FirewallRuleRepository rules, FirewallIntegrationRepository integrations, FirewallRuleService service,
            FirewallService firewalls, AgentTaskRepository tasks, Clock clock) {
        this.rules = rules;
        this.integrations = integrations;
        this.service = service;
        this.firewalls = firewalls;
        this.tasks = tasks;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT20S")
    @Transactional
    public void run() {
        Instant now = clock.instant();
        // 1. Expiry. A request that was never decided simply lapses.
        for (FirewallRule r : rules.findByStatusInAndExpiresAtBefore(List.of("REQUESTED"), now)) {
            integrations.findById(r.getIntegrationId()).ifPresent(i -> service.setStatus(r, i, "EXPIRED",
                    "The request expired before anyone decided it", "system"));
        }
        for (FirewallRule r : rules.findByStatusInAndExpiresAtBefore(List.of("APPROVED", "APPLIED", "DRY_RUN", "UNVERIFIED", "FAILED"), now)) {
            integrations.findById(r.getIntegrationId()).ifPresent(i -> {
                r.requestRemoval("system", "Expired", true, now);
                service.startRemoval(r, i, "system", now);
                log.info("Firewall rule {} expired", r.getTag());
            });
        }
        // 2. Tasks the agent never confirmed.
        Instant cutoff = now.minus(firewalls.taskTimeout());
        for (String status : List.of("APPROVED", "REMOVAL_PENDING")) {
            for (FirewallRule r : rules.findByStatus(status)) {
                Instant since = r.getUpdatedAt();
                if (since == null || since.isAfter(cutoff)) continue;
                integrations.findById(r.getIntegrationId()).ifPresent(i -> {
                    boolean delivered = tasks.findByFirewallRuleIdOrderByCreatedAtDesc(r.getId()).stream().findFirst()
                            .map(t -> "DELIVERED".equals(t.getStatus())).orElse(false);
                    tasks.findByFirewallRuleIdAndStatus(r.getId(), "PENDING").forEach(AgentTask::cancel);
                    String why = delivered ? "The agent received the task but never reported a result"
                            : "The agent did not collect the task (offline, or firewall support not enabled on the server)";
                    // A removal that never completed may have left the rule in place: say so.
                    service.setStatus(r, i, "APPROVED".equals(status) ? "FAILED" : "UNVERIFIED",
                            why + ("APPROVED".equals(status) ? "" : "; the rule may still be present on the server"), "system");
                });
            }
        }
    }
}
