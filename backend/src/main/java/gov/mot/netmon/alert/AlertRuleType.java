package gov.mot.netmon.alert;

import gov.mot.netmon.event.EventCategory;

/**
 * Supported alert conditions. {@code unit} tells the UI how to show the threshold;
 * {@code resourceKind} says what the optional resource filter matches.
 */
public enum AlertRuleType {
    CPU_HIGH(EventCategory.PERFORMANCE, Unit.PERCENT, 1, 100, true, null, true),
    MEMORY_HIGH(EventCategory.PERFORMANCE, Unit.PERCENT, 1, 100, true, null, true),
    SWAP_HIGH(EventCategory.PERFORMANCE, Unit.PERCENT, 1, 100, true, null, true),
    DISK_USAGE_HIGH(EventCategory.PERFORMANCE, Unit.PERCENT, 1, 100, true, "MOUNT", true),
    NETWORK_THROUGHPUT_HIGH(EventCategory.NETWORK, Unit.BITS_PER_SECOND, 1_000, 1e12, true, "INTERFACE", true),
    TRAFFIC_SPIKE(EventCategory.NETWORK, Unit.RATIO, 1.5, 1000, true, "INTERFACE", true),
    INTERFACE_ERRORS(EventCategory.NETWORK, Unit.PER_SECOND, 0.01, 1e9, true, "INTERFACE", true),
    AGENT_OFFLINE(EventCategory.AVAILABILITY, Unit.SECONDS, 60, 86400, false, null, true),
    SERVICE_NOT_RUNNING(EventCategory.SERVICE, Unit.NONE, 0, 0, true, "SERVICE", true),
    AGENT_DEGRADED(EventCategory.AGENT, Unit.NONE, 0, 0, true, "COLLECTOR", true),
    FAILED_LOGINS(EventCategory.SECURITY, Unit.COUNT, 1, 100_000, true, "IP", false),
    CAPTURE_STORAGE_HIGH(EventCategory.CAPTURE, Unit.PERCENT, 1, 100, false, null, false),
    FIREWALL_DRIFT(EventCategory.FIREWALL, Unit.NONE, 0, 0, false, null, true);

    public enum Unit { PERCENT, BITS_PER_SECOND, RATIO, PER_SECOND, SECONDS, COUNT, NONE }

    public final EventCategory category;
    public final Unit unit;
    public final double minThreshold;
    public final double maxThreshold;
    public final boolean usesWindow;
    public final String resourceKind;
    public final boolean serverScoped;

    AlertRuleType(EventCategory category, Unit unit, double min, double max, boolean usesWindow, String resourceKind,
            boolean serverScoped) {
        this.category = category;
        this.unit = unit;
        this.minThreshold = min;
        this.maxThreshold = max;
        this.usesWindow = usesWindow;
        this.resourceKind = resourceKind;
        this.serverScoped = serverScoped;
    }

    public boolean needsThreshold() {
        return unit != Unit.NONE;
    }
}
