package gov.mot.netmon.audit;

/** Every auditable action. New phases add values here (capture start, firewall apply, ...). */
public enum AuditAction {
    // authentication
    AUTH_LOGIN,
    AUTH_LOGIN_FAILED,
    AUTH_LOGOUT,
    AUTH_TOKEN_REFRESH_REUSE_DETECTED,
    AUTH_PASSWORD_CHANGED,
    ACCESS_DENIED,
    // first-run setup
    SETUP_ADMIN_CREATED,
    SETUP_ATTEMPT_FAILED,
    // users
    USER_CREATED,
    USER_UPDATED,
    USER_ACTIVATED,
    USER_DEACTIVATED,
    USER_UNLOCKED,
    USER_PASSWORD_RESET,
    // servers
    SERVER_REGISTERED,
    SERVER_UPDATED,
    SERVER_DELETED,
    SERVER_GROUP_CREATED,
    SERVER_GROUP_UPDATED,
    SERVER_GROUP_DELETED
}
