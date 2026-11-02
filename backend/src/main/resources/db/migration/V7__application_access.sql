-- Phase 8: application inventory and application-specific network access policies.

-- An application is identified on its server by an identity the operating system can enforce:
--   LINUX_USER       the account its processes run as (nftables "meta skuid")
--   WINDOWS_PROGRAM  the full path of its executable (Windows Defender Firewall -Program)
--   WINDOWS_SERVICE  its Windows service name (Windows Defender Firewall -Service)
-- A business name alone can never be enforced by an IP firewall.
CREATE TABLE application_inventory (
    id             UUID PRIMARY KEY,
    server_id      UUID         NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    name           VARCHAR(120) NOT NULL,
    description    VARCHAR(500),
    owner          VARCHAR(120),
    identity_kind  VARCHAR(20)  NOT NULL CHECK (identity_kind IN ('LINUX_USER', 'WINDOWS_PROGRAM', 'WINDOWS_SERVICE')),
    identity_value VARCHAR(260) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    created_by     VARCHAR(50)  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    updated_by     VARCHAR(50)  NOT NULL,
    UNIQUE (server_id, name),
    UNIQUE (server_id, identity_kind, identity_value)
);

-- Application access policies use the same rule table, workflow, approvals, verification and
-- expiry as IP rules. For them remote_cidr is empty and the policy is in app_mode/destinations.
ALTER TABLE firewall_rules ALTER COLUMN remote_cidr DROP NOT NULL;
ALTER TABLE firewall_rules ADD COLUMN application_id UUID REFERENCES application_inventory (id) ON DELETE RESTRICT;
ALTER TABLE firewall_rules ADD COLUMN application_label VARCHAR(300);
ALTER TABLE firewall_rules ADD COLUMN app_mode VARCHAR(20)
    CHECK (app_mode IN ('BLOCK_INTERNET', 'ALLOW_ONLY', 'BLOCK_DESTINATIONS'));
ALTER TABLE firewall_rules ADD COLUMN destinations TEXT;  -- JSON list of {cidr, protocol, port}
ALTER TABLE firewall_rules ADD CONSTRAINT firewall_rules_kind_check
    CHECK ((application_id IS NULL AND remote_cidr IS NOT NULL AND app_mode IS NULL)
        OR (application_id IS NOT NULL AND remote_cidr IS NULL AND app_mode IS NOT NULL));
CREATE INDEX idx_firewall_rules_application ON firewall_rules (application_id) WHERE application_id IS NOT NULL;
