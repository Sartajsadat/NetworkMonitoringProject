-- Phase 1: users, roles, sessions, server inventory, server groups, audit trail.
-- All timestamps are timestamptz and written in UTC by the application.

-- ---------------------------------------------------------------------------
-- Roles and users
-- ---------------------------------------------------------------------------
CREATE TABLE roles (
    id          SMALLSERIAL PRIMARY KEY,
    code        VARCHAR(40)  NOT NULL UNIQUE,
    name        VARCHAR(80)  NOT NULL,
    description VARCHAR(500) NOT NULL
);

INSERT INTO roles (code, name, description) VALUES
 ('SUPER_ADMIN', 'Super administrator',
  'Manage users, roles, monitored infrastructure, security settings and global configuration.'),
 ('INFRASTRUCTURE_ADMIN', 'Infrastructure administrator',
  'Manage registered servers, monitoring configuration, services and infrastructure alerts.'),
 ('NETWORK_ADMIN', 'Network administrator',
  'View network traffic, investigate packets, manage authorized IP policies and network integrations.'),
 ('SECURITY_ANALYST', 'Security analyst',
  'Review alerts, packet metadata, audit history and incident timelines. Read-only for disruptive actions.'),
 ('READ_ONLY', 'Read-only user',
  'View permitted dashboards, metrics, reports and historical events.');

CREATE TABLE users (
    id                  UUID PRIMARY KEY,
    username            VARCHAR(50)  NOT NULL UNIQUE,   -- stored lower-case
    email               VARCHAR(254) NOT NULL UNIQUE,   -- stored lower-case
    full_name           VARCHAR(120) NOT NULL,
    password_hash       VARCHAR(200) NOT NULL,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_count  INTEGER      NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    locked_until        TIMESTAMPTZ,
    -- Incremented on deactivation / password reset / logout-all so that
    -- outstanding access tokens stop working immediately.
    token_version       INTEGER      NOT NULL DEFAULT 0,
    last_login_at       TIMESTAMPTZ,
    password_changed_at TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT users_username_format CHECK (username ~ '^[a-z0-9._-]{3,50}$'),
    CONSTRAINT users_email_lower CHECK (email = lower(email))
);

CREATE TABLE user_roles (
    user_id UUID     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id SMALLINT NOT NULL REFERENCES roles (id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,          -- SHA-256 hex; raw token never stored
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by UUID,
    created_at  TIMESTAMPTZ  NOT NULL,
    created_ip  VARCHAR(45),
    user_agent  VARCHAR(255)
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);

-- Small key/value store for platform-level flags (e.g. first-run setup completed).
CREATE TABLE system_settings (
    key        VARCHAR(100) PRIMARY KEY,
    value      VARCHAR(2000) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

-- ---------------------------------------------------------------------------
-- Server inventory
-- ---------------------------------------------------------------------------
CREATE TABLE servers (
    id                UUID PRIMARY KEY,
    name              VARCHAR(120) NOT NULL,
    hostname          VARCHAR(253) NOT NULL,
    os_family         VARCHAR(20)  NOT NULL CHECK (os_family IN ('LINUX', 'WINDOWS', 'OTHER')),
    os_version        VARCHAR(120),
    environment       VARCHAR(20)  NOT NULL
                      CHECK (environment IN ('DEVELOPMENT', 'TESTING', 'STAGING', 'PRODUCTION')),
    organization      VARCHAR(150) NOT NULL,
    purpose           VARCHAR(500),
    location          VARCHAR(120),
    monitoring_status VARCHAR(20)  NOT NULL
                      CHECK (monitoring_status IN ('PENDING_AGENT', 'ACTIVE', 'PAUSED', 'DECOMMISSIONED')),
    -- Agent fields are written by the agent channel (Phase 2); NULL means "no agent has reported".
    agent_version     VARCHAR(40),
    agent_health      VARCHAR(20)  NOT NULL DEFAULT 'NO_AGENT'
                      CHECK (agent_health IN ('NO_AGENT', 'HEALTHY', 'DEGRADED', 'UNREACHABLE')),
    last_seen_at      TIMESTAMPTZ,
    notes             VARCHAR(2000),
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(50),
    updated_by        VARCHAR(50)
);
CREATE UNIQUE INDEX uq_servers_name ON servers (lower(name));
CREATE INDEX idx_servers_environment ON servers (environment);
CREATE INDEX idx_servers_os_family ON servers (os_family);
CREATE INDEX idx_servers_organization ON servers (organization);
CREATE INDEX idx_servers_hostname ON servers (lower(hostname));

CREATE TABLE server_ip_addresses (
    id         BIGSERIAL PRIMARY KEY,
    server_id  UUID        NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    ip_address VARCHAR(45) NOT NULL,           -- canonical textual form, validated by the application
    ip_version SMALLINT    NOT NULL CHECK (ip_version IN (4, 6)),
    label      VARCHAR(60),
    UNIQUE (server_id, ip_address)
);
CREATE INDEX idx_server_ip_addresses_ip ON server_ip_addresses (ip_address);

CREATE TABLE server_groups (
    id          UUID PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    group_type  VARCHAR(20)  NOT NULL
                CHECK (group_type IN ('DEPARTMENT', 'ENVIRONMENT', 'APPLICATION', 'SERVER_ROLE', 'CUSTOM')),
    description VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    UNIQUE (group_type, name)
);

CREATE TABLE server_group_members (
    group_id  UUID NOT NULL REFERENCES server_groups (id) ON DELETE CASCADE,
    server_id UUID NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, server_id)
);
CREATE INDEX idx_server_group_members_server ON server_group_members (server_id);

-- ---------------------------------------------------------------------------
-- Audit trail (append-only)
-- ---------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id             BIGSERIAL PRIMARY KEY,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    actor_user_id  UUID,
    actor_username VARCHAR(50),
    actor_roles    VARCHAR(300),
    action         VARCHAR(60)  NOT NULL,
    target_type    VARCHAR(40),
    target_id      VARCHAR(100),
    result         VARCHAR(20)  NOT NULL CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED')),
    source_ip      VARCHAR(45),
    request_id     VARCHAR(64),
    reason         VARCHAR(500),
    details        JSONB
);
CREATE INDEX idx_audit_logs_occurred ON audit_logs (occurred_at DESC);
CREATE INDEX idx_audit_logs_actor ON audit_logs (actor_username, occurred_at DESC);
CREATE INDEX idx_audit_logs_action ON audit_logs (action, occurred_at DESC);
CREATE INDEX idx_audit_logs_target ON audit_logs (target_type, target_id);

-- Tamper resistance by design: the application role cannot change or remove audit rows.
-- Retention (Phase 9) will use a dedicated, separately-privileged procedure that
-- disables this trigger inside its own transaction and records its own audit entry.
CREATE FUNCTION audit_logs_block_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only (% blocked)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_logs_no_update
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_logs_block_modification();

CREATE TRIGGER trg_audit_logs_no_truncate
    BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION audit_logs_block_modification();
