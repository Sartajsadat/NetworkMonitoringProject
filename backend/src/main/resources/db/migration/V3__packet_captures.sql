-- Phase 4: packet captures. PCAPNG files live on disk (CAPTURE_STORAGE_DIR), never in the database.
-- The database keeps capture requests, file bookkeeping and per-packet METADATA (no payload).

CREATE TABLE packet_captures (
    id                UUID PRIMARY KEY,
    server_id         UUID         NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    name              VARCHAR(120) NOT NULL,
    description       VARCHAR(1000),
    reference         VARCHAR(120),               -- ticket / investigation reference
    reason            VARCHAR(500) NOT NULL,      -- approved reason (required)
    interface_name    VARCHAR(128) NOT NULL,
    capture_filter    VARCHAR(512),               -- BPF, applied by dumpcap on the server
    snaplen           INTEGER      NOT NULL CHECK (snaplen BETWEEN 64 AND 65535),
    duration_seconds  INTEGER      NOT NULL CHECK (duration_seconds BETWEEN 10 AND 86400),
    max_bytes         BIGINT       NOT NULL CHECK (max_bytes > 0),
    retention_days    INTEGER      NOT NULL CHECK (retention_days BETWEEN 1 AND 365),
    status            VARCHAR(20)  NOT NULL
                      CHECK (status IN ('PENDING', 'STARTING', 'RUNNING', 'STOPPING', 'COMPLETED', 'FAILED',
                                        'UNSUPPORTED', 'STOPPED')),
    status_message    VARCHAR(1000),
    requested_by      VARCHAR(50)  NOT NULL,
    requested_at      TIMESTAMPTZ  NOT NULL,
    started_at        TIMESTAMPTZ,
    ended_at          TIMESTAMPTZ,
    stop_requested_by VARCHAR(50),
    stop_requested_at TIMESTAMPTZ,
    agent_packets     BIGINT,                     -- as counted by dumpcap on the server
    bytes_stored      BIGINT       NOT NULL DEFAULT 0,
    segments_count    INTEGER      NOT NULL DEFAULT 0,
    packets_indexed   BIGINT       NOT NULL DEFAULT 0,
    index_truncated   BOOLEAN      NOT NULL DEFAULT FALSE,
    expires_at        TIMESTAMPTZ  NOT NULL,
    files_deleted_at  TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_packet_captures_server ON packet_captures (server_id, requested_at DESC);
CREATE INDEX idx_packet_captures_status ON packet_captures (status);
CREATE INDEX idx_packet_captures_expires ON packet_captures (expires_at) WHERE files_deleted_at IS NULL;

-- One row per uploaded PCAPNG piece (dumpcap rotates files every few seconds; pieces are uploaded
-- while the capture runs, so packets can be analysed live).
CREATE TABLE capture_segments (
    id           BIGSERIAL PRIMARY KEY,
    capture_id   UUID        NOT NULL REFERENCES packet_captures (id) ON DELETE CASCADE,
    seq          INTEGER     NOT NULL,
    file_name    VARCHAR(200) NOT NULL,
    size_bytes   BIGINT      NOT NULL,
    sha256       VARCHAR(64) NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL,
    first_frame  BIGINT,                          -- global frame number of the first packet
    packet_count INTEGER,
    index_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (index_status IN ('PENDING', 'DONE', 'FAILED')),
    index_error  VARCHAR(500),
    UNIQUE (capture_id, seq)
);

-- Typed tasks for agents (delivered when the agent polls). The agent executes only known task types.
CREATE TABLE agent_tasks (
    id           UUID PRIMARY KEY,
    server_id    UUID        NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    capture_id   UUID        REFERENCES packet_captures (id) ON DELETE CASCADE,
    task_type    VARCHAR(30) NOT NULL CHECK (task_type IN ('START_CAPTURE', 'STOP_CAPTURE')),
    payload      JSONB       NOT NULL,
    status       VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'DELIVERED', 'CANCELLED')),
    created_at   TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ
);
CREATE INDEX idx_agent_tasks_pending ON agent_tasks (server_id, created_at) WHERE status = 'PENDING';

-- Packet metadata only (addresses, ports, protocol, length, flags, short summary). No payload bytes.
CREATE TABLE packet_summaries (
    capture_id     UUID        NOT NULL REFERENCES packet_captures (id) ON DELETE CASCADE,
    frame_no       BIGINT      NOT NULL,          -- global number across all segments, starting at 1
    segment_seq    INTEGER     NOT NULL,
    segment_frame  INTEGER     NOT NULL,          -- frame number inside the segment file
    captured_at    TIMESTAMPTZ NOT NULL,
    src            VARCHAR(64),
    dst            VARCHAR(64),
    src_port       INTEGER,
    dst_port       INTEGER,
    protocol       VARCHAR(40),
    length         INTEGER     NOT NULL,
    tcp_flags      VARCHAR(10),
    retransmission BOOLEAN     NOT NULL DEFAULT FALSE,
    tcp_reset      BOOLEAN     NOT NULL DEFAULT FALSE,
    dns_query      VARCHAR(255),
    info           VARCHAR(300),
    PRIMARY KEY (capture_id, frame_no)
);
CREATE INDEX idx_packet_summaries_src ON packet_summaries (capture_id, src);
CREATE INDEX idx_packet_summaries_dst ON packet_summaries (capture_id, dst);
CREATE INDEX idx_packet_summaries_proto ON packet_summaries (capture_id, protocol);
CREATE INDEX idx_packet_summaries_time ON packet_summaries (capture_id, captured_at);
