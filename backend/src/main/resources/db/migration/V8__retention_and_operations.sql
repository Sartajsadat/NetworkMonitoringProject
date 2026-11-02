-- Phase 9: retention policies managed in the UI, and a safe purge path for the audit log.

CREATE TABLE retention_policies (
    data_type      VARCHAR(30)  PRIMARY KEY,
    retention_days INTEGER      NOT NULL,
    min_days       INTEGER      NOT NULL,
    max_days       INTEGER      NOT NULL,
    CHECK (retention_days BETWEEN min_days AND max_days),
    -- 'system' = still following the environment default; anything else = changed by that administrator
    updated_by     VARCHAR(50)  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    last_run_at    TIMESTAMPTZ,
    last_deleted   BIGINT,
    last_error     VARCHAR(500)
);

INSERT INTO retention_policies (data_type, retention_days, min_days, max_days, updated_by, updated_at) VALUES
('METRICS_RAW', 7, 1, 90, 'system', now()),
('METRICS_ROLLUP', 90, 7, 1825, 'system', now()),
('EVENTS', 180, 7, 1825, 'system', now()),
('ALERTS_RESOLVED', 365, 30, 1825, 'system', now()),
('FLOWS', 14, 1, 365, 'system', now()),
('CAPTURES', 90, 1, 365, 'system', now()),
('FIREWALL_HISTORY', 730, 90, 3650, 'system', now()),
('AUDIT_LOGS', 1095, 365, 3650, 'system', now());

-- The audit log stays append-only, with one exception: the retention procedure may delete entries
-- that are older than one year. The age floor is enforced here, in the database, so nobody using
-- the application's account can delete recent audit history.
CREATE OR REPLACE FUNCTION audit_logs_block_modification() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE'
       AND current_setting('netmon.audit_purge', true) = 'on'
       AND OLD.occurred_at < now() - interval '365 days' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'audit_logs is append-only (% blocked)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION purge_audit_logs(cutoff TIMESTAMPTZ) RETURNS BIGINT AS $$
DECLARE
    n BIGINT;
BEGIN
    PERFORM set_config('netmon.audit_purge', 'on', true);
    DELETE FROM audit_logs WHERE occurred_at < LEAST(cutoff, now() - interval '365 days');
    GET DIAGNOSTICS n = ROW_COUNT;
    PERFORM set_config('netmon.audit_purge', 'off', true);
    RETURN n;
END;
$$ LANGUAGE plpgsql;

-- Monitoring of the platform itself: the disk holding packet capture files.
INSERT INTO alert_rules (id, name, description, rule_type, severity, threshold, window_seconds, cooldown_seconds,
                         enabled, builtin, created_at, created_by, updated_at, updated_by) VALUES
('00000000-0000-4000-8000-00000000000d', 'Platform disk almost full',
 'Used space of the file system that holds the platform''s capture files', 'PLATFORM_DISK_LOW', 'ERROR',
 90, 0, 3600, TRUE, TRUE, now(), 'system', now(), 'system');
