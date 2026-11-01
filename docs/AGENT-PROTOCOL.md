# Agent ↔ platform protocol (version 1)

All calls are HTTPS `POST` with JSON bodies, initiated by the agent. Paths are under `/api/v1/agent`
and use a separate security chain from the user API: user tokens are not accepted there, and
agent credentials are not accepted anywhere else.

## Enrollment — `POST /api/v1/agent/enroll` (no credential; one-time token in the body)

```json
{ "token": "…", "hostname": "web-01", "platform": "LINUX", "osName": "Ubuntu", "osVersion": "24.04",
  "kernel": "6.8.0-45-generic", "architecture": "amd64", "agentVersion": "0.2.0", "machineId": "<sha-256 prefix>" }
```

Response: `{ "agentId", "secret", "serverId", "serverName", "config": {…} }`.
The platform stores only `SHA-256(secret)`. `machineId` is a hash of `/etc/machine-id`, never the raw value.
Errors: `401 INVALID_ENROLLMENT_TOKEN`, `429 RATE_LIMITED`.

## Authentication

```
Authorization: Agent <agentId>:<secret>
```

The secret is 256 random bits. `401 AGENT_REVOKED` means an administrator revoked the agent; the
agent then stops reporting and retries every 10 minutes (in case it was re-enrolled locally).
Mutual TLS with per-agent certificates is planned for phase 9.

## Report — `POST /api/v1/agent/report`

Sent every `reportIntervalSeconds`. Every field is optional except `agentVersion`, `collectedAt`
and `host`; **a missing value means "not measured" and is never stored as zero**. Rates
(`…PerSec`, `usagePercent`) are computed by the agent from counter differences and are absent in
the first report after start.

```json
{
  "agentVersion": "0.2.0", "collectedAt": "2026-11-01T10:00:00Z", "intervalSeconds": 30,
  "host":   { "hostname": "web-01", "platform": "LINUX", "osName": "Ubuntu", "osVersion": "24.04",
              "kernel": "6.8.0", "architecture": "amd64", "uptimeSeconds": 3600, "bootTime": "…", "cpuCount": 4 },
  "cpu":    { "usagePercent": 12.5, "iowaitPercent": 0.4, "perCore": [10, 15, 9, 16],
              "load1": 0.5, "load5": 0.4, "load15": 0.3, "runQueue": 2 },
  "memory": { "totalBytes": 8e9, "availableBytes": 6e9, "usedBytes": 2e9, "usedPercent": 25,
              "swapTotalBytes": 0, "swapUsedBytes": 0 },
  "disks":  [ { "mount": "/", "device": "/dev/sda1", "fsType": "ext4", "totalBytes": 0, "usedBytes": 0,
                "freeBytes": 0, "usedPercent": 40 } ],
  "diskIo": [ { "device": "sda", "readBytesPerSec": 0, "writeBytesPerSec": 0, "readOpsPerSec": 0,
                "writeOpsPerSec": 0, "busyPercent": 0 } ],
  "network":[ { "name": "eth0", "operState": "up", "macAddress": "52:54:…", "addresses": ["10.0.0.5/24"],
                "speedMbps": 1000, "mtu": 1500, "rxBytes": 0, "txBytes": 0, "rxPackets": 0, "txPackets": 0,
                "rxErrors": 0, "txErrors": 0, "rxDropped": 0, "txDropped": 0,
                "rxBitsPerSec": 0, "txBitsPerSec": 0, "rxPacketsPerSec": 0, "txPacketsPerSec": 0,
                "errorsPerSec": 0, "dropsPerSec": 0 } ],
  "services":[ { "name": "nginx.service", "status": "RUNNING", "loadState": "loaded",
                 "activeState": "active", "subState": "running", "since": "…" } ],
  "processes":[ { "pid": 1234, "name": "java", "user": "app", "cpuPercent": 4.2, "memoryBytes": 0,
                  "memoryPercent": 1.1, "state": "S", "startedAt": "…" } ],
  "collectors":[ { "name": "services", "status": "OK|UNSUPPORTED|UNAVAILABLE|PERMISSION_DENIED|ERROR", "message": "…" } ]
}
```

Limits (anything larger is rejected with `400 VALIDATION_FAILED`): 64 disks / disk devices /
interfaces, 100 services, 30 processes, 30 collector entries, bounded string lengths, percentages 0–100.

Service `status`: `RUNNING`, `STOPPED`, `FAILED`, `STARTING`, `STOPPING`, `NOT_FOUND`, `UNKNOWN`.

Processes: top 15 by CPU plus top 15 by memory. **Command lines and environment variables are never
collected** (they can contain passwords).

Response:

```json
{ "serverTime": "…", "accepted": true, "note": null,
  "config": { "reportIntervalSeconds": 30, "services": ["nginx.service"], "monitoringPaused": false } }
```

`config` is the only way the platform influences the agent: typed settings, no commands. When the
server is *Paused* or *Decommissioned*, the heartbeat is recorded but metrics are not stored
(`accepted: false`, `monitoringPaused: true`).

Errors: `400 CLOCK_SKEW` (more than 5 min in the future), `400 REPORT_TOO_OLD` (older than 48 h),
`400 VALIDATION_FAILED`. The agent drops a report rejected with 400 and retries on 5xx/network errors.

## Windows agents

Same format, `platform: "WINDOWS"`. Differences: `cpu.load1/5/15` and `cpu.iowaitPercent` are
absent (Windows has no such values) and `cpu.runQueue` is the processor queue length; the page file
is reported in the `swap…` fields; disk `mount` is the drive (`C:\`); `diskIo.device` is the physical
disk instance (`0_C:`); interface names may contain spaces and brackets; process `user` is absent;
service `loadState` is the start mode (Auto/Manual/Disabled) and `activeState` the Windows state.

## Credential rotation — `POST /api/v1/agent/credentials/rotate`

Returns `{ "secret", "previousSecretValidUntil" }`. The agent saves the new secret to disk **before**
using it; the old one keeps working for `AGENT_CREDENTIAL_GRACE_PERIOD` (10 min).
