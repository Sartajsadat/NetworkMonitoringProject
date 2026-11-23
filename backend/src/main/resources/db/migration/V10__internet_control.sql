-- (V9 is the earlier website-blocking schema kept for databases that already applied it.)
-- Internet control: websites blocked by name on one computer. The agent writes the names with
-- blocked = TRUE into NetMon's own section of the computer's hosts file (when the computer's
-- administrator enabled it with sites.enabled=true) and reports back what is really there.
CREATE TABLE blocked_sites (
    id         UUID PRIMARY KEY,
    server_id  UUID         NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    domain     VARCHAR(253) NOT NULL,
    blocked    BOOLEAN      NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    created_by VARCHAR(50)  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    updated_by VARCHAR(50)  NOT NULL,
    UNIQUE (server_id, domain)
);
