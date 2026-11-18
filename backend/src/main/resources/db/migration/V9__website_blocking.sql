-- Website blocking: a platform-wide list of websites with an on/off switch each. Agents on servers and
-- staff PCs receive the blocked ones with their settings, write them into the device's hosts file and
-- report what is really there (device_website_state). Nothing here touches a physical firewall.
CREATE TABLE blocked_websites (
    id          UUID PRIMARY KEY,
    domain      VARCHAR(253) NOT NULL UNIQUE,
    blocked     BOOLEAN      NOT NULL,
    note        VARCHAR(200),
    created_by  VARCHAR(50)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(50)  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE device_website_state (
    server_id     UUID PRIMARY KEY REFERENCES servers (id) ON DELETE CASCADE,
    enabled       BOOLEAN      NOT NULL,
    message       VARCHAR(300),
    blocked       TEXT         NOT NULL DEFAULT '',  -- domains read back from the hosts file, one per line
    collected_at  TIMESTAMPTZ  NOT NULL,
    received_at   TIMESTAMPTZ  NOT NULL
);
