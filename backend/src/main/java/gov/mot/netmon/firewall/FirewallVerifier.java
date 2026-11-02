package gov.mot.netmon.firewall;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import gov.mot.netmon.agent.AgentProtocol;
import gov.mot.netmon.server.Server;

/**
 * Compares the NetMon rule tags each agent reads from its firewall with the platform's rules.
 * <ul>
 * <li>APPLIED but missing on the server: UNVERIFIED (someone removed it, or the firewall was reloaded);
 * <li>UNVERIFIED and present again: APPLIED;
 * <li>a NetMon tag on the server that is not an active rule: "unknown rule" event (once per tag).
 * </ul>
 * Runs inside the report transaction.
 */
@Component
public class FirewallVerifier {

    private final FirewallIntegrationRepository integrations;
    private final FirewallRuleRepository rules;
    private final FirewallRuleService service;
    private final FirewallEvents events;
    private final Clock clock;
    private final Map<UUID, Set<String>> reportedUnknown = new ConcurrentHashMap<>();

    public FirewallVerifier(FirewallIntegrationRepository integrations, FirewallRuleRepository rules, FirewallRuleService service,
            FirewallEvents events, Clock clock) {
        this.integrations = integrations;
        this.rules = rules;
        this.service = service;
        this.events = events;
        this.clock = clock;
    }

    public void onReport(Server server, AgentProtocol.Report report) {
        AgentProtocol.Firewall fw = report.firewall();
        if (fw == null || !fw.enabled() || fw.ruleTags() == null) return;
        Set<String> present = new HashSet<>(fw.ruleTags());
        Instant collected = report.collectedAt();
        Instant now = clock.instant();
        String actor = "agent@" + server.getName();
        Set<String> known = new HashSet<>();
        integrations.findByServerId(server.getId()).ifPresent(i -> {
            for (FirewallRule r : rules.findByIntegrationIdAndStatusIn(i.getId(), List.of("APPLIED", "UNVERIFIED", "REMOVAL_PENDING"))) {
                known.add(r.getTag());
                boolean there = present.contains(r.getTag());
                // A report collected before the rule was applied says nothing about it.
                boolean fresh = (r.getAppliedAt() == null || collected.isAfter(r.getAppliedAt()))
                        // The agent removes expired rules by itself; that is not drift.
                        && (r.getExpiresAt() == null || collected.isBefore(r.getExpiresAt()));
                switch (r.getStatus()) {
                    case "APPLIED" -> {
                        if (there) r.verified(now);
                        else if (fresh) service.setStatus(r, i, "UNVERIFIED",
                                "The rule is no longer present in the server's firewall (removed outside the platform, or the firewall was reloaded)", actor);
                    }
                    case "UNVERIFIED" -> {
                        if (there && fresh) service.setStatus(r, i, "APPLIED", "The rule is present again", actor);
                    }
                    default -> {
                    }
                }
            }
        });
        Set<String> seen = reportedUnknown.computeIfAbsent(server.getId(), k -> ConcurrentHashMap.newKeySet());
        seen.retainAll(present);
        for (String tag : present) {
            if (known.contains(tag) || seen.contains(tag)) continue;
            // Recently removed rules may still be listed in a report collected before the removal.
            boolean recentlyGone = rules.findByTag(tag).map(r -> r.getRemovedAt() != null && !collected.isAfter(r.getRemovedAt())
                    || "APPROVED".equals(r.getStatus())).orElse(false);
            if (recentlyGone) continue;
            seen.add(tag);
            events.unknownRule(server.getId(), server.getName(), tag);
        }
    }
}
