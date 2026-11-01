-- Phase 5: event timeline, alert rules and alerts.

-- ---------------------------------------------------------------------------
-- Alert rules: thresholds, evaluation window, cooldown, optional server scope.
-- ---------------------------------------------------------------------------
CREATE TABLE alert_rules (
    id               UUID PRIMARY KEY,
    name             VARCHAR(120) NOT NULL,
    description      VARCHAR(500),
    rule_type        VARCHAR(40)  NOT NULL,
    severity         VARCHAR(10)  NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'ERROR', 'CRITICAL')),
    threshold        DOUBLE PRECISION,
    window_seconds   INTEGER      NOT NULL CHECK (window_seconds BETWEEN 0 AND 86400),
    cooldown_seconds INTEGER      NOT NULL CHECK (cooldown_seconds BETWEEN 0 AND 604800),
    server_id        UUID REFERENCES servers (id) ON DELETE CASCADE,   -- NULL = every server
    resource_filter  VARCHAR(255),                                     -- mount, interface or service; NULL = all
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    builtin          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(50)  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    updated_by       VARCHAR(50)  NOT NULL
);

-- ---------------------------------------------------------------------------
-- Alerts: one row per incident of a rule on one resource. Deduplicated: while an alert
-- is OPEN or ACKNOWLEDGED, repeated triggers update it instead of creating new rows.
-- ---------------------------------------------------------------------------
CREATE TABLE alerts (
    id                 UUID PRIMARY KEY,
    rule_id            UUID REFERENCES alert_rules (id) ON DELETE SET NULL,
    rule_name          VARCHAR(120) NOT NULL,
    rule_type          VARCHAR(40)  NOT NULL,
    category           VARCHAR(20)  NOT NULL,
    severity           VARCHAR(10)  NOT NULL,
    server_id          UUID REFERENCES servers (id) ON DELETE SET NULL,
    server_name        VARCHAR(120),
    resource           VARCHAR(255),
    dedup_key          VARCHAR(400) NOT NULL,
    status             VARCHAR(15)  NOT NULL CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    condition_text     VARCHAR(500) NOT NULL,
    threshold          DOUBLE PRECISION,
    window_seconds     INTEGER      NOT NULL,
    first_triggered_at TIMESTAMPTZ  NOT NULL,
    last_triggered_at  TIMESTAMPTZ  NOT NULL,
    last_value         DOUBLE PRECISION,
    peak_value         DOUBLE PRECISION,
    evaluations        INTEGER      NOT NULL DEFAULT 1,
    acknowledged_at    TIMESTAMPTZ,
    acknowledged_by    VARCHAR(50),
    ack_comment        VARCHAR(1000),
    resolved_at        TIMESTAMPTZ,
    resolved_by        VARCHAR(50),
    resolution_type    VARCHAR(15) CHECK (resolution_type IN ('AUTO', 'MANUAL', 'RULE_CHANGED')),
    resolution         VARCHAR(1000)
);
CREATE UNIQUE INDEX uq_alerts_active_dedup ON alerts (dedup_key) WHERE status <> 'RESOLVED';
CREATE INDEX idx_alerts_status ON alerts (status, last_triggered_at DESC);
CREATE INDEX idx_alerts_server ON alerts (server_id, first_triggered_at DESC);
CREATE INDEX idx_alerts_dedup_resolved ON alerts (dedup_key, resolved_at DESC);

-- ---------------------------------------------------------------------------
-- Events: the timeline. Written by the platform (agent reports, alert engine, captures,
-- security) - never edited. Server name is copied so history survives server deletion.
-- ---------------------------------------------------------------------------
CREATE TABLE events (
    id             BIGSERIAL PRIMARY KEY,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    received_at    TIMESTAMPTZ  NOT NULL,
    category       VARCHAR(20)  NOT NULL
                   CHECK (category IN ('PERFORMANCE', 'AVAILABILITY', 'SERVICE', 'NETWORK', 'CAPTURE', 'SECURITY',
                                       'AGENT', 'SYSTEM')),
    event_type     VARCHAR(40)  NOT NULL,
    severity       VARCHAR(10)  NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'ERROR', 'CRITICAL')),
    source         VARCHAR(10)  NOT NULL CHECK (source IN ('AGENT', 'PLATFORM')),
    server_id      UUID REFERENCES servers (id) ON DELETE SET NULL,
    server_name    VARCHAR(120),
    resource       VARCHAR(255),
    ip_address     VARCHAR(45),
    summary        VARCHAR(500) NOT NULL,
    details        JSONB,
    correlation_id UUID,
    alert_id       UUID REFERENCES alerts (id) ON DELETE SET NULL,
    capture_id     UUID REFERENCES packet_captures (id) ON DELETE SET NULL
);
CREATE INDEX idx_events_time ON events (occurred_at DESC);
CREATE INDEX idx_events_server_time ON events (server_id, occurred_at DESC);
CREATE INDEX idx_events_category_time ON events (category, occurred_at DESC);
CREATE INDEX idx_events_correlation ON events (correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX idx_events_alert ON events (alert_id) WHERE alert_id IS NOT NULL;
CREATE INDEX idx_events_ip ON events (ip_address) WHERE ip_address IS NOT NULL;

-- Agent connection state, so a disconnection is reported once and paired with its reconnection.
ALTER TABLE agents ADD COLUMN disconnected_at TIMESTAMPTZ;
ALTER TABLE agents ADD COLUMN disconnect_correlation UUID;

-- ---------------------------------------------------------------------------
-- Default rules (editable). Throughput rules are off: good limits depend on each network.
-- ---------------------------------------------------------------------------
INSERT INTO alert_rules (id, name, description, rule_type, severity, threshold, window_seconds, cooldown_seconds,
                         enabled, builtin, created_at, created_by, updated_at, updated_by) VALUES
('00000000-0000-4000-8000-000000000001', 'High CPU usage', 'Average CPU usage over the window', 'CPU_HIGH', 'WARNING', 90, 300, 900, TRUE, TRUE, now(), 'system', now(), 'system'),
('00000000-0000-4000-8000-000000000002', 'High memory usage', 'Average memory usage over the window', 'MEMORY_HIGH', 'WARNING', 95, 300, 900, TRUE, TRUE, now(), 'system', now(), 'system'),
('00000000-0000-4000-8000-000000000003', 'Disk almost full', 'Used space of a mounted file system or drive', 'DISK_USAGE_HIGH', 'WARNING', 90, 300, 3600, TRUE, TRUE, now(), 'system', now(), 'system'),
('00000000-0000-4000-8000-000000000004', 'Agent not reporting', 'No report from the agent for longer than the threshold (seconds)', 'AGENT_OFFLINE', 'CRITICAL', 300, 0, 600, TRUE, TRUE, now(), 'system', now(), 'system'),
('00000000-0000-4000-8000-000000000005', 'Monitored service not running', 'A service on the agent''s list is stopped, failed or missing', 'SERVICE_NOT_RUNNING', 'ERROR', NULL, 60, 600, TRUE, TRUE, now(), 'system', now(), 'system'),
('00000000-0000-4000-8000-000000000006', 'Agent collector problems', 'A collector reports an error or a missing permission', 'AGENT_DEGRADED', 'WARNING', NULL, 300, 3600, TRUE, TRUE, now(), 'system', now(), 'system'),
('00000000-0000-4000-8000-000000000007', 'Interface errors or drops', 'Receive/transmit errors plus drops per second on an interface', 'INTERFACE_ERRORS', 'WARNING', 10, 300, 1800, TRUE, TRUE, now(), 'system', now(), 'system'),
('00000000-0000-4000-8000-000000000008', 'Repeated failed sign-ins', 'Failed sign-ins from one address within the window', 'FAILED_LOGINS', 'WARNING', 10, 900, 1800, TRUE, TRUE, now(), 'system', now(), 'system'),
('00000000-0000-4000-8000-000000000009', 'Capture storage almost full', 'Packet capture files use this share of the storage quota', 'CAPTURE_STORAGE_HIGH', 'WARNING', 80, 0, 3600, TRUE, TRUE, now(), 'system', now(), 'system'),
('00000000-0000-4000-8000-00000000000a', 'High network throughput', 'Average receive or transmit rate of an interface (bits per second)', 'NETWORK_THROUGHPUT_HIGH', 'WARNING', 800000000, 300, 1800, FALSE, TRUE, now(), 'system', now(), 'system'),
('00000000-0000-4000-8000-00000000000b', 'Unusual traffic increase', 'Traffic over the window is this many times the average of the previous 24 hours', 'TRAFFIC_SPIKE', 'WARNING', 5, 300, 3600, FALSE, TRUE, now(), 'system', now(), 'system');
