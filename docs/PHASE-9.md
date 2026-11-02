# Phase 9 — Production hardening

**Goal:** review authentication and agent security, monitor the platform itself, configure backup and
restoration, enforce log and packet retention, document TLS, certificates, credentials, ports,
deployment and recovery, and run security and performance tests.

**Result:** done. Tests:

| Part | Tests |
|---|---|
| Backend | 143 (new: retention, audit-log protection, platform health, platform-disk alert, 5 API-wide security tests) |
| Agent | 58 |
| Frontend | 88 (3 new) |

`npm audit`: 0 vulnerabilities.

## 1. Retention and storage management (`/admin/retention`)
- **One policy per kind of data:**
  - raw metrics;
  - 5-minute averages;
  - events;
  - resolved alerts;
  - flow records;
  - capture files;
  - ended firewall rules;
  - audit log.
- **Who:** super administrators change the policies, within safe limits and with a reason (audited).
  Infrastructure administrators can view them.
- **Defaults:** until changed, each policy follows the deployment's environment variables, so
  existing settings keep working.
- **Enforcement:** one job applies all policies every hour; "Run clean-up now" applies them at once.
  Each run records what it deleted or why it failed, and manual runs are audited.
- **Storage view:** shows the size, approximate rows and oldest record of each kind of data.
- **Capture files:** the policy is both a hard limit on stored files and the longest retention a new
  capture may request.
- **Audit log:** still append-only. The only exception is a database function that can delete entries
  **older than one year**; the floor is checked inside the database trigger. Even with the
  application's own database account, recent audit history cannot be deleted (tested).

## 2. Monitoring the platform itself (`/admin/health`)
- **Database:** version, schema version, size, connections, clock difference, largest tables.
- **Storage:** capture disk usage and free space, capture quota.
- **Agents:** how many report, and their versions.
- **Jobs:** retention runs and failures, pending agent tasks, flow collector.
- **Effective settings:** listed under their `.env` variable names; secrets are never shown.
- **Checks** (OK / Warning / Problem) for:
  - database reachable;
  - disk above 85% / 95%;
  - capture quota above 80%;
  - agents not reporting;
  - retention errors;
  - clock difference;
  - collector down;
  - insecure cookie setting;
  - wildcard CORS.
- **New default alert rule "Platform disk almost full"** (90%) for the capture disk. The platform
  server's own CPU, memory and other disks are covered by installing an agent on it.
- **Docker Compose:**
  - health checks for all three services;
  - log rotation (3 × 20 MB);
  - backend memory limit (`BACKEND_MEMORY`);
  - all Linux capabilities dropped and `no-new-privileges` for the backend and frontend;
  - the frontend waits for a healthy backend.

## 3. Backup and restore (tested)
`deploy/backup/backup.sh` and `restore.sh`:
- **What is saved:** a pg_dump (checked with `pg_restore --list`) and the capture files.
- **Protection:** SHA-256 checksums, optional AES-256 encryption, pruning of old backups.
- **Restore:** a single-transaction database restore, and a schema-version check before anything is
  replaced.

Tested on the live lab database:

| Test | Result |
|---|---|
| Damage and restore | Data restored exactly |
| Altered backup | Refused |
| Wrong passphrase | Refused |
| Audit protection after restore | Still active |

Details: [BACKUP.md](BACKUP.md).

## 4. TLS (tested)
- **Proxy configuration:** `deploy/tls/netmon-tls.conf` (host nginx) gives TLS 1.2/1.3 only, HSTS,
  HTTP→HTTPS redirect, overwritten X-Forwarded-For, and 70 MB uploads for capture pieces.
- **Client addresses:** the frontend nginx now trusts the forwarded client address only from
  loopback and Docker networks, so audit records and rate limits see real client addresses behind
  the proxy.

Tested with a lab CA:

| Test | Result |
|---|---|
| Valid certificate | Works over HTTP/2 with HSTS |
| Untrusted CA | Refused |
| Wrong host name | Refused |
| TLS 1.1 | Refused |
| Agent with `--ca` | Enrolled and reported over TLS |
| Agent without `--ca` | Refused ("PKIX path building failed") |

## 5. Security review and tests
See [SECURITY.md](SECURITY.md) for the full list of controls, configuration, credential rotation and
incident response.

New `SecurityHardeningTest` discovers every API endpoint from the code (more than 100) and checks:
- all need a sign-in;
- a read-only user cannot change anything;
- user and agent credentials are not interchangeable;
- security headers are present;
- injection attempts and malformed input give clean 4xx, never 500 or internals;
- the actuator exposes only health;
- unknown cross-origin requests are refused.

**Defect found and fixed:** a wrong `Content-Type` returned 500 instead of 415.

`install-agent.sh` gained `--allow-insecure-http`, for isolated labs only (it was documented but
missing).

## 6. Performance test (measured)
`tools/loadtest/agent_load.py` (Python standard library only) enrolls simulated agents and sends
full reports. Each report has CPU, memory, 3 disks, 4 interfaces, 20 services and 30 processes. At
the same time, 4 readers request the dashboard, 24-hour CPU history and the event timeline.

Run on a 4-core / 15 GB machine:
- 100 agents every 10 s (the load of 300 servers at the normal 30 s);
- 4 minutes;
- backend and PostgreSQL on the same machine.

| Measure | Result |
|---|---|
| Reports | 2 400 in 250 s (9.6/s), **0 errors** |
| Report latency | p50 16 ms · p95 30 ms · p99 51 ms · max 88 ms |
| Dashboard | p50 78 ms · p95 109 ms |
| 24-hour CPU history | p50 6 ms · p95 14 ms |
| Event timeline | p50 7 ms · p95 13 ms |
| Read errors | 0 (4 830 requests) |
| Backend | about 25% of one core, 0.7 GB memory |
| Storage | about 15 values per report, about 170 bytes per value with indexes |

The sizing table in [DEPLOYMENT.md](DEPLOYMENT.md) is based on these figures.

**Finding:** enrolment shares the sign-in rate limit (20 per minute per address). Enrolling many
servers from one address is therefore slow. This is intended brute-force protection and is
documented.

## 7. Documentation added
| Document | Contents |
|---|---|
| [DEPLOYMENT.md](DEPLOYMENT.md) | Sizing, host preparation, install, TLS, platform monitoring, upgrade and rollback, retention |
| [SECURITY.md](SECURITY.md) | Controls, what to configure, optional separate database roles, credential rotation, incident response |
| [BACKUP.md](BACKUP.md) | Backup, restore, test results, restore drill |
| [INSTALL-UBUNTU.md](INSTALL-UBUNTU.md) | Ubuntu test environment with agent, capture and firewall |
| [INSTALL-WINDOWS-DEV.md](INSTALL-WINDOWS-DEV.md) | Windows development environment |
| [DIAGRAMS.md](DIAGRAMS.md) | Architecture, network communication with ports, database ER diagram |
| [KNOWN-LIMITATIONS.md](KNOWN-LIMITATIONS.md) | Not tested, not supported, not built, operational limits |

## 8. Still open
Everything open is listed in [KNOWN-LIMITATIONS.md](KNOWN-LIMITATIONS.md). The main items:
- lab tests on Windows Server and on UFW;
- the ministry's firewall models (for central adapters and syslog parsing);
- the "Phase 5b" features (centralized logs, hosted-application inventory);
- notifications (email or other channels).
