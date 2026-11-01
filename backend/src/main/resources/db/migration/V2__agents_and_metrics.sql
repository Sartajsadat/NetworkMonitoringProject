-- Phase 2: monitoring agents, enrollment, latest snapshots and metric history.

-- ---------------------------------------------------------------------------
-- Agents (one per server; re-enrollment replaces the credential, history is in audit_logs)
-- ---------------------------------------------------------------------------
CREATE TABLE agents (
    id                          UUID PRIMARY KEY,
    server_id                   UUID         NOT NULL UNIQUE REFERENCES servers (id) ON DELETE CASCADE,
    status                      VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    -- SHA-256 of the agent secret. The raw secret exists only on the monitored server.
    secret_hash                 VARCHAR(64)  NOT NULL,
    -- After a rotation the previous secret stays valid for a short grace period.
    previous_secret_hash        VARCHAR(64),
    previous_secret_valid_until TIMESTAMPTZ,
    agent_version               VARCHAR(40),
    platform                    VARCHAR(20),
    os_name                     VARCHAR(120),
    os_version                  VARCHAR(120),
    kernel                      VARCHAR(120),
    architecture                VARCHAR(40),
    reported_hostname           VARCHAR(253),
    machine_id                  VARCHAR(64),
    enrolled_at                 TIMESTAMPTZ  NOT NULL,
    enrolled_from_ip            VARCHAR(45),
    last_seen_at                TIMESTAMPTZ,
    last_report_ip              VARCHAR(45),
    last_error_count            INTEGER      NOT NULL DEFAULT 0,
    credential_rotated_at       TIMESTAMPTZ,
    revoked_at                  TIMESTAMPTZ,
    revoked_by                  VARCHAR(50)
);
CREATE INDEX idx_agents_status ON agents (status);

CREATE TABLE agent_enrollment_tokens (
    id          UUID PRIMARY KEY,
    server_id   UUID        NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    created_by  VARCHAR(50)
);
CREATE INDEX idx_enrollment_tokens_server ON agent_enrollment_tokens (server_id);

-- Per-server agent settings, kept across re-enrollments and delivered in each report response.
CREATE TABLE server_agent_configs (
    server_id               UUID PRIMARY KEY REFERENCES servers (id) ON DELETE CASCADE,
    report_interval_seconds INTEGER     NOT NULL DEFAULT 30 CHECK (report_interval_seconds BETWEEN 10 AND 3600),
    -- systemd unit names whose state the agent reports (allowlist; the agent never controls them)
    monitored_services      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    updated_at              TIMESTAMPTZ NOT NULL,
    updated_by              VARCHAR(50)
);

-- ---------------------------------------------------------------------------
-- Latest full report per server (current view: interfaces, disks, services, processes)
-- ---------------------------------------------------------------------------
CREATE TABLE server_snapshots (
    server_id    UUID PRIMARY KEY REFERENCES servers (id) ON DELETE CASCADE,
    collected_at TIMESTAMPTZ NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL,
    payload      JSONB       NOT NULL
);

-- ---------------------------------------------------------------------------
-- Metric history. Narrow layout: one row per (server, metric, resource, time).
-- `resource` is '' for host-wide metrics, else a mount point, disk device or interface name.
-- Raw rows are kept for a short period; 5-minute rollups for longer (see MetricRetentionJob).
-- ---------------------------------------------------------------------------
CREATE TABLE metric_samples (
    server_id    UUID             NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    metric       VARCHAR(40)      NOT NULL,
    resource     VARCHAR(128)     NOT NULL DEFAULT '',
    collected_at TIMESTAMPTZ      NOT NULL,
    value        DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (server_id, metric, resource, collected_at)
);
CREATE INDEX idx_metric_samples_collected ON metric_samples (collected_at);

CREATE TABLE metric_rollups_5m (
    server_id    UUID             NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    metric       VARCHAR(40)      NOT NULL,
    resource     VARCHAR(128)     NOT NULL DEFAULT '',
    bucket_start TIMESTAMPTZ      NOT NULL,
    avg_value    DOUBLE PRECISION NOT NULL,
    min_value    DOUBLE PRECISION NOT NULL,
    max_value    DOUBLE PRECISION NOT NULL,
    sample_count INTEGER          NOT NULL,
    PRIMARY KEY (server_id, metric, resource, bucket_start)
);
CREATE INDEX idx_metric_rollups_bucket ON metric_rollups_5m (bucket_start);
