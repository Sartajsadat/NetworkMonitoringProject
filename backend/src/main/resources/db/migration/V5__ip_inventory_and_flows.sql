-- Phase 6: subnets, network devices, IP address inventory and flow records.

CREATE TABLE subnets (
    id           UUID PRIMARY KEY,
    cidr         VARCHAR(50)  NOT NULL UNIQUE,        -- canonical network address/prefix (host bits zero)
    ip_version   INTEGER      NOT NULL CHECK (ip_version IN (4, 6)),
    prefix       INTEGER      NOT NULL,
    name         VARCHAR(120) NOT NULL,
    description  VARCHAR(500),
    vlan_id      INTEGER CHECK (vlan_id BETWEEN 1 AND 4094),
    gateway      VARCHAR(45),
    location     VARCHAR(120),
    organization VARCHAR(150),
    created_at   TIMESTAMPTZ  NOT NULL,
    created_by   VARCHAR(50)  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    updated_by   VARCHAR(50)  NOT NULL
);

-- Routers, switches, firewalls and other network equipment. A device that exports flows must be
-- registered here with its exporter address: flows from unknown senders are dropped.
CREATE TABLE network_devices (
    id                UUID PRIMARY KEY,
    name              VARCHAR(120) NOT NULL UNIQUE,
    device_type       VARCHAR(20)  NOT NULL
                      CHECK (device_type IN ('ROUTER', 'SWITCH', 'FIREWALL', 'LOAD_BALANCER', 'WIRELESS', 'SENSOR', 'OTHER')),
    vendor            VARCHAR(80),
    model             VARCHAR(80),
    management_ip     VARCHAR(45),
    location          VARCHAR(120),
    description       VARCHAR(500),
    flow_export       BOOLEAN      NOT NULL DEFAULT FALSE,
    exporter_ip       VARCHAR(45),                     -- source address of its NetFlow/IPFIX packets
    last_flow_at      TIMESTAMPTZ,
    flows_received    BIGINT       NOT NULL DEFAULT 0,
    flows_dropped     BIGINT       NOT NULL DEFAULT 0,
    last_flow_version INTEGER,
    sampling_interval INTEGER,
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(50)  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(50)  NOT NULL
);
CREATE UNIQUE INDEX uq_network_devices_exporter ON network_devices (exporter_ip) WHERE exporter_ip IS NOT NULL;

-- One row per known address. Filled passively (server registry, agents, neighbour tables, flows inside
-- registered subnets, device management addresses) or by hand/CSV import. Never by scanning.
CREATE TABLE ip_addresses (
    id                UUID PRIMARY KEY,
    address           VARCHAR(45)  NOT NULL UNIQUE,     -- canonical text form
    ip_version        SMALLINT     NOT NULL CHECK (ip_version IN (4, 6)),
    subnet_id         UUID REFERENCES subnets (id) ON DELETE SET NULL,
    server_id         UUID REFERENCES servers (id) ON DELETE SET NULL,
    device_id         UUID REFERENCES network_devices (id) ON DELETE SET NULL,
    mac_address       VARCHAR(17),
    hostname          VARCHAR(253),
    allocation        VARCHAR(15)  NOT NULL DEFAULT 'UNKNOWN'
                      CHECK (allocation IN ('UNKNOWN', 'ASSIGNED', 'RESERVED', 'DHCP', 'GATEWAY')),
    sources           VARCHAR(200) NOT NULL DEFAULT '',  -- comma-separated discovery sources
    first_seen_at     TIMESTAMPTZ,
    last_seen_at      TIMESTAMPTZ,
    last_seen_source  VARCHAR(20),
    notes             VARCHAR(1000),
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_ip_addresses_subnet ON ip_addresses (subnet_id);
CREATE INDEX idx_ip_addresses_server ON ip_addresses (server_id);
CREATE INDEX idx_ip_addresses_mac ON ip_addresses (mac_address);

-- Flow records (NetFlow v5/v9, IPFIX). A flow record is a summary written by the exporter,
-- not a packet: there is no payload and no per-packet detail.
CREATE TABLE network_flows (
    id            BIGSERIAL PRIMARY KEY,
    exporter_id   UUID REFERENCES network_devices (id) ON DELETE CASCADE,
    received_at   TIMESTAMPTZ NOT NULL,
    flow_start    TIMESTAMPTZ NOT NULL,
    flow_end      TIMESTAMPTZ NOT NULL,
    src_ip        INET        NOT NULL,
    dst_ip        INET        NOT NULL,
    src_port      INTEGER,
    dst_port      INTEGER,
    protocol      SMALLINT    NOT NULL,
    bytes         BIGINT      NOT NULL,
    packets       BIGINT      NOT NULL,
    tcp_flags     SMALLINT,
    input_if      INTEGER,
    output_if     INTEGER,
    sampling      INTEGER     NOT NULL DEFAULT 1,       -- 1:N packet sampling reported by the exporter
    version       SMALLINT    NOT NULL
);
CREATE INDEX idx_network_flows_end ON network_flows (flow_end DESC);
CREATE INDEX idx_network_flows_src ON network_flows (src_ip, flow_end);
CREATE INDEX idx_network_flows_dst ON network_flows (dst_ip, flow_end);
