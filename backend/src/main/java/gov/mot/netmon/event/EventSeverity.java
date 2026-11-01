package gov.mot.netmon.event;

public enum EventSeverity {
    INFO, WARNING, ERROR, CRITICAL;

    public boolean atLeast(EventSeverity other) {
        return ordinal() >= other.ordinal();
    }
}
