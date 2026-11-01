package gov.mot.netmon.event;

/** What an event is about. SECURITY events are visible only to security roles. */
public enum EventCategory {
    PERFORMANCE, AVAILABILITY, SERVICE, NETWORK, CAPTURE, SECURITY, AGENT, SYSTEM
}
