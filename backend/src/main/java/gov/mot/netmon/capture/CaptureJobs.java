package gov.mot.netmon.capture;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.config.AppProperties;

/**
 * Housekeeping: fails captures the agent never picked up or stopped confirming, and deletes files
 * and packet metadata when the retention period ends.
 */
@Component
public class CaptureJobs {

    private static final Logger log = LoggerFactory.getLogger(CaptureJobs.class);
    static final Duration STOP_CONFIRM_TIMEOUT = Duration.ofMinutes(10);
    static final Duration RUN_GRACE = Duration.ofMinutes(10);

    private final PacketCaptureRepository captures;
    private final AgentTaskRepository tasks;
    private final CaptureService service;
    private final Clock clock;
    private final Duration pendingTimeout;

    private final CaptureEvents captureEvents;

    public CaptureJobs(PacketCaptureRepository captures, AgentTaskRepository tasks, CaptureService service, Clock clock,
            AppProperties props, CaptureEvents captureEvents) {
        this.captureEvents = captureEvents;
        this.captures = captures;
        this.tasks = tasks;
        this.service = service;
        this.clock = clock;
        this.pendingTimeout = props.capture().pendingTimeout();
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    @Transactional
    public void timeouts() {
        Instant now = clock.instant();
        for (PacketCapture c : captures.findByStatusIn(CaptureStatus.ACTIVE)) {
            String reason = null;
            if (c.getStatus() == CaptureStatus.PENDING && c.getRequestedAt().isBefore(now.minus(pendingTimeout))) {
                reason = "The agent did not pick up the capture request in time (is it online and capture-enabled?)";
                tasks.findByCaptureIdAndStatus(c.getId(), "PENDING").forEach(AgentTask::cancel);
            } else if (c.getStatus() == CaptureStatus.STOPPING && c.getStopRequestedAt() != null
                    && c.getStopRequestedAt().isBefore(now.minus(STOP_CONFIRM_TIMEOUT))) {
                reason = "The agent did not confirm that the capture stopped";
            } else if ((c.getStatus() == CaptureStatus.RUNNING || c.getStatus() == CaptureStatus.STARTING)
                    && c.getUpdatedAt().isBefore(now.minus(RUN_GRACE))
                    && (c.getStartedAt() == null ? c.getRequestedAt() : c.getStartedAt())
                            .plusSeconds(c.getDurationSeconds()).isBefore(now.minus(RUN_GRACE))) {
                reason = "The agent stopped reporting on this capture";
            }
            if (reason != null) {
                CaptureStatus before = c.getStatus();
                c.transition(CaptureStatus.FAILED, reason, now);
                captureEvents.statusChanged(c, before, CaptureStatus.FAILED);
                log.warn("Capture {} marked FAILED: {}", c.getId(), reason);
            }
        }
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional
    public void retention() {
        List<PacketCapture> expired = captures.findByExpiresAtBeforeAndFilesDeletedAtIsNull(clock.instant());
        for (PacketCapture c : expired) {
            if (c.getStatus().isActive()) continue;
            service.purge(c, "retention", "Retention period of " + c.getRetentionDays() + " days ended");
        }
    }

    /** Retention policy: files of captures requested before the cutoff (whatever their own retention). */
    @Transactional
    public int purgeOlderThan(Instant cutoff, int days) {
        int n = 0;
        for (PacketCapture c : captures.findByRequestedAtBeforeAndFilesDeletedAtIsNull(cutoff)) {
            if (c.getStatus().isActive()) continue;
            service.purge(c, "retention", "Capture retention policy of " + days + " days");
            n++;
        }
        return n;
    }
}
