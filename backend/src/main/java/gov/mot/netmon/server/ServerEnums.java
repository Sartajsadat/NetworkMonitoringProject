package gov.mot.netmon.server;

/** Enumerations for the server inventory (values match CHECK constraints in V1__foundation.sql). */
public final class ServerEnums {

    private ServerEnums() {
    }

    public enum OsFamily { LINUX, WINDOWS, OTHER }

    public enum Environment { DEVELOPMENT, TESTING, STAGING, PRODUCTION }

    /** ACTIVE is set only by the agent channel (Phase 2) once real data arrives. */
    public enum MonitoringStatus { PENDING_AGENT, ACTIVE, PAUSED, DECOMMISSIONED }

    public enum AgentHealth { NO_AGENT, HEALTHY, DEGRADED, UNREACHABLE }

    /** Derived connection state shown on dashboards. */
    public enum ConnectionStatus { ONLINE, OFFLINE, NO_AGENT }

    public enum GroupType { DEPARTMENT, ENVIRONMENT, APPLICATION, SERVER_ROLE, CUSTOM }
}
