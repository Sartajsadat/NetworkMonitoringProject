package gov.mot.netmon.agent.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Settings delivered by the platform in enroll/report responses. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentConfig(int reportIntervalSeconds, List<String> services, boolean monitoringPaused, List<String> blockedSites) {

    /** {@code blockedSites} is null until the platform sends a list: nothing is changed before that. */
    public static AgentConfig defaults() {
        return new AgentConfig(30, List.of(), false, null);
    }

    public AgentConfig sanitized() {
        int interval = Math.max(10, Math.min(3600, reportIntervalSeconds <= 0 ? 30 : reportIntervalSeconds));
        return new AgentConfig(interval, services == null ? List.of() : List.copyOf(services), monitoringPaused,
                blockedSites == null ? null : blockedSites.stream().filter(java.util.Objects::nonNull).toList());
    }
}
