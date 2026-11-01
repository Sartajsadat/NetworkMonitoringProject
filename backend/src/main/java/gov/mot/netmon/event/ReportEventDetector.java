package gov.mot.netmon.event;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import gov.mot.netmon.agent.AgentProtocol.CollectorStatus;
import gov.mot.netmon.agent.AgentProtocol.Report;
import gov.mot.netmon.agent.AgentProtocol.Service;
import gov.mot.netmon.server.Server;

/**
 * Compares a new agent report with the previous one and records what changed: service state
 * changes and restarts, server reboots, collectors that started or stopped failing. Events carry
 * the time the agent observed them (or the restart/boot time it reported), not the arrival time.
 */
@Component
public class ReportEventDetector {

    /** Boot or restart times that move by less than this are measurement noise, not a restart. */
    static final Duration JITTER = Duration.ofSeconds(90);

    private final EventService events;

    public ReportEventDetector(EventService events) {
        this.events = events;
    }

    public void compare(Server server, Report previous, Report current) {
        if (previous == null || !current.collectedAt().isAfter(previous.collectedAt())) return;
        reboot(server, previous, current);
        services(server, previous, current);
        collectors(server, previous, current);
    }

    private void reboot(Server server, Report previous, Report current) {
        Instant before = previous.host() == null ? null : previous.host().bootTime();
        Instant now = current.host() == null ? null : current.host().bootTime();
        if (before == null || now == null || !now.isAfter(before.plus(JITTER))) return;
        events.event(EventCategory.AVAILABILITY, "SERVER_REBOOTED", EventSeverity.WARNING).fromAgent()
                .server(server.getId(), server.getName()).at(now.isAfter(current.collectedAt()) ? current.collectedAt() : now)
                .summary("Server restarted (boot time " + now + ")").detail("bootTime", now).detail("previousBootTime", before)
                .record();
    }

    private void services(Server server, Report previous, Report current) {
        Map<String, Service> old = byName(previous.services(), Service::name);
        for (Service s : current.services() == null ? List.<Service>of() : current.services()) {
            Service p = old.get(s.name());
            if (p == null) continue; // newly monitored: no change to report
            if (!p.status().equals(s.status())) {
                EventSeverity sev = switch (s.status()) {
                    case "FAILED", "NOT_FOUND" -> EventSeverity.ERROR;
                    case "STOPPED" -> EventSeverity.WARNING;
                    default -> EventSeverity.INFO;
                };
                Instant at = s.since() != null && s.since().isAfter(previous.collectedAt()) && !s.since().isAfter(current.collectedAt())
                        ? s.since() : current.collectedAt();
                events.event(EventCategory.SERVICE, "SERVICE_STATE_CHANGED", sev).fromAgent()
                        .server(server.getId(), server.getName()).resource(s.name()).at(at)
                        .summary("Service " + s.name() + " changed from " + p.status() + " to " + s.status())
                        .detail("service", s.name()).detail("from", p.status()).detail("to", s.status())
                        .detail("activeState", s.activeState()).detail("subState", s.subState()).record();
            } else if ("RUNNING".equals(s.status()) && p.since() != null && s.since() != null
                    && s.since().isAfter(p.since().plus(JITTER))) {
                // Still running, but started again since the previous report: it restarted in between.
                events.event(EventCategory.SERVICE, "SERVICE_RESTARTED", EventSeverity.WARNING).fromAgent()
                        .server(server.getId(), server.getName()).resource(s.name())
                        .at(s.since().isAfter(current.collectedAt()) ? current.collectedAt() : s.since())
                        .summary("Service " + s.name() + " restarted").detail("service", s.name()).detail("since", s.since())
                        .detail("previousSince", p.since()).record();
            }
        }
    }

    private void collectors(Server server, Report previous, Report current) {
        Map<String, CollectorStatus> old = byName(previous.collectors(), CollectorStatus::name);
        for (CollectorStatus c : current.collectors() == null ? List.<CollectorStatus>of() : current.collectors()) {
            CollectorStatus p = old.get(c.name());
            if (p == null || Objects.equals(p.status(), c.status())) continue;
            boolean nowBad = isProblem(c.status());
            boolean wasBad = isProblem(p.status());
            if (nowBad && !wasBad) {
                events.event(EventCategory.AGENT, "COLLECTOR_PROBLEM", EventSeverity.WARNING).fromAgent()
                        .server(server.getId(), server.getName()).resource(c.name()).at(current.collectedAt())
                        .summary("Collector " + c.name() + ": " + c.status() + (c.message() == null ? "" : " - " + c.message()))
                        .detail("collector", c.name()).detail("status", c.status()).detail("message", c.message()).record();
            } else if (!nowBad && wasBad) {
                events.event(EventCategory.AGENT, "COLLECTOR_RECOVERED", EventSeverity.INFO).fromAgent()
                        .server(server.getId(), server.getName()).resource(c.name()).at(current.collectedAt())
                        .summary("Collector " + c.name() + " works again").detail("collector", c.name()).record();
            }
        }
    }

    public static boolean isProblem(String status) {
        return "ERROR".equals(status) || "PERMISSION_DENIED".equals(status);
    }

    private static <T> Map<String, T> byName(List<T> list, Function<T, String> name) {
        return list == null ? Map.of() : list.stream().collect(Collectors.toMap(name, x -> x, (a, b) -> a));
    }
}
