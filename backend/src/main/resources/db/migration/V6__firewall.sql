-- Phase 7: firewall integrations, IP allow/block rules with approval, verification and expiry.

-- Where rules can be enforced. SERVER_AGENT = the host firewall of one server, through its agent
-- (a rule there never affects other systems). CENTRAL_DRY_RUN = a placeholder for a central
-- firewall whose vendor/API is not yet known: it validates and previews, it never changes a device.
CREATE TABLE firewall_integrations (
    id              UUID PRIMARY KEY,
    name            VARCHAR(120) NOT NULL UNIQUE,
    kind            VARCHAR(20)  NOT NULL CHECK (kind IN ('SERVER_AGENT', 'CENTRAL_DRY_RUN')),
    server_id       UUID UNIQUE REFERENCES servers (id) ON DELETE CASCADE,
    mode            VARCHAR(10)  NOT NULL DEFAULT 'DRY_RUN' CHECK (mode IN ('DRY_RUN', 'ENFORCE')),
    description     VARCHAR(500),
    mode_changed_by VARCHAR(50),
    mode_changed_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(50)  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CHECK ((kind = 'SERVER_AGENT') = (server_id IS NOT NULL))
);

CREATE TABLE firewall_rules (
    id                  UUID PRIMARY KEY,
    integration_id      UUID         NOT NULL REFERENCES firewall_integrations (id) ON DELETE CASCADE,
    tag                 VARCHAR(16)  NOT NULL UNIQUE,     -- written into the rule (comment/name) to find it again
    action              VARCHAR(5)   NOT NULL CHECK (action IN ('ALLOW', 'BLOCK')),
    direction           VARCHAR(8)   NOT NULL CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    remote_cidr         VARCHAR(50)  NOT NULL,            -- the other side: source of inbound, destination of outbound
    local_cidr          VARCHAR(50),                      -- this side; NULL = any address of the target
    protocol            VARCHAR(5)   NOT NULL CHECK (protocol IN ('ANY', 'TCP', 'UDP', 'ICMP')),
    local_port          VARCHAR(11),                      -- "22" or "8000-8080" (TCP/UDP only)
    remote_port         VARCHAR(11),
    reason              VARCHAR(500) NOT NULL,
    reference           VARCHAR(120),
    expires_at          TIMESTAMPTZ,
    status              VARCHAR(16)  NOT NULL CHECK (status IN ('REQUESTED', 'REJECTED', 'CANCELLED', 'APPROVED',
                            'APPLIED', 'DRY_RUN', 'FAILED', 'UNVERIFIED', 'REMOVAL_PENDING', 'REMOVED', 'EXPIRED')),
    status_message      VARCHAR(1000),
    mode_at_apply       VARCHAR(10),
    preview             VARCHAR(2000),
    warnings            VARCHAR(2000),
    agent_command       VARCHAR(2000),                    -- what the agent actually ran (or would run)
    requested_by        VARCHAR(50)  NOT NULL,
    requested_at        TIMESTAMPTZ  NOT NULL,
    decided_by          VARCHAR(50),
    decided_at          TIMESTAMPTZ,
    applied_at          TIMESTAMPTZ,
    last_verified_at    TIMESTAMPTZ,
    removal_requested_by VARCHAR(50),
    removal_requested_at TIMESTAMPTZ,
    removal_reason      VARCHAR(500),
    removed_at          TIMESTAMPTZ,
    expiring            BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at          TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_firewall_rules_status ON firewall_rules (status);
CREATE INDEX idx_firewall_rules_integration ON firewall_rules (integration_id, requested_at DESC);

-- Every decision on a rule (four-eyes: the approver is never the requester).
CREATE TABLE access_policy_approvals (
    id          BIGSERIAL PRIMARY KEY,
    rule_id     UUID        NOT NULL REFERENCES firewall_rules (id) ON DELETE CASCADE,
    decision    VARCHAR(10) NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    decided_by  VARCHAR(50) NOT NULL,
    decided_at  TIMESTAMPTZ NOT NULL,
    comment     VARCHAR(1000)
);

-- Agent tasks for firewall changes.
ALTER TABLE agent_tasks DROP CONSTRAINT agent_tasks_task_type_check;
ALTER TABLE agent_tasks ADD CONSTRAINT agent_tasks_task_type_check
    CHECK (task_type IN ('START_CAPTURE', 'STOP_CAPTURE', 'APPLY_FIREWALL_RULE', 'REMOVE_FIREWALL_RULE'));
ALTER TABLE agent_tasks ADD COLUMN firewall_rule_id UUID REFERENCES firewall_rules (id) ON DELETE CASCADE;

-- Firewall changes are events too.
ALTER TABLE events DROP CONSTRAINT events_category_check;
ALTER TABLE events ADD CONSTRAINT events_category_check CHECK (category IN ('PERFORMANCE', 'AVAILABILITY', 'SERVICE',
    'NETWORK', 'CAPTURE', 'SECURITY', 'AGENT', 'SYSTEM', 'FIREWALL'));

-- "Unexpected firewall change": a rule the platform applied is no longer present on the target.
INSERT INTO alert_rules (id, name, description, rule_type, severity, threshold, window_seconds, cooldown_seconds,
                         enabled, builtin, created_at, created_by, updated_at, updated_by) VALUES
('00000000-0000-4000-8000-00000000000c', 'Unexpected firewall change',
 'A rule the platform applied is missing on the server, or an unknown NetMon rule appeared', 'FIREWALL_DRIFT', 'ERROR',
 NULL, 0, 1800, TRUE, TRUE, now(), 'system', now(), 'system');
