package gov.mot.netmon.capture;

import java.util.EnumSet;
import java.util.Set;

/**
 * PENDING: waiting for the agent to pick up the request. STARTING/RUNNING/STOPPING: reported by the
 * agent. COMPLETED/FAILED/UNSUPPORTED: final, reported by the agent (or FAILED by a timeout).
 * STOPPED: stopped before the agent started it.
 */
public enum CaptureStatus {
    PENDING, STARTING, RUNNING, STOPPING, COMPLETED, FAILED, UNSUPPORTED, STOPPED;

    public static final Set<CaptureStatus> ACTIVE = EnumSet.of(PENDING, STARTING, RUNNING, STOPPING);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }
}
