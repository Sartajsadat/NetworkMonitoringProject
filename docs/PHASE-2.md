# Phase 2 — Linux monitoring

**Goal:** a Linux agent that reports real CPU, memory, disk, network, uptime and service data;
current and historical views; heartbeat and failure reporting.

**Result:** done. 76 backend, 17 agent and 47 frontend tests pass. The real agent was enrolled
and run against the full Docker stack on Ubuntu 24.04, including a simulated 45-second platform
outage (no data was lost: queued reports were delivered afterwards with no gap in the charts).

---

## 1. What was built, and how

### 1.1 The agent (`agent/`)

- Plain Java 21 + Jackson (one 2.5 MB jar). No web server, no remote-command library.
- **Linux collectors** read kernel files directly:

  | Collector | Source | Notes |
  |---|---|---|
  | host | `/proc/uptime`, `/proc/sys/kernel/*`, `/etc/os-release` | hostname, OS, kernel, uptime, CPU count |
  | cpu | `/proc/stat`, `/proc/loadavg` | usage and I/O wait from jiffy deltas, per-core, load, run queue |
  | memory | `/proc/meminfo` | used = MemTotal − MemAvailable; swap |
  | disks | `/proc/mounts` + `statvfs` | real file systems only (ext4, xfs, …), percentage like `df` |
  | diskio | `/proc/diskstats` | whole disks only; bytes/s, ops/s, busy % |
  | network | `/sys/class/net/*` | state, MAC, IPs, speed, counters, bits/s, packets/s, errors/s, drops/s |
  | services | `systemctl show <unit> --property=…` | only units on the platform's allowlist; fixed executable path, argument list, no shell, 5 s timeout |
  | processes | `/proc/[pid]/stat`, `status` | top 15 by CPU + top 15 by memory; **no command lines** |

- Each collector runs independently. A failure becomes a status (`UNSUPPORTED`, `UNAVAILABLE`,
  `PERMISSION_DENIED`, `ERROR`) in the report; the server then shows **Degraded** and the Agent
  tab says which collector failed. Values are never invented.
- Rates need two samples, so the first report after start has totals but no rates. Counter
  resets (reboot, driver reload) give "no value", not a negative or huge spike.
- **Collection and delivery are separate threads.** Collection stays on schedule; if the platform
  is down, reports wait in a bounded queue (240) and are sent oldest-first later.
- Commands: `enroll`, `run`, `collect-once` (local test, sends nothing), `version`.

### 1.2 Enrollment and agent security

1. An admin creates a one-time token for a specific server (24 h, single use, stored hashed).
2. The agent exchanges it for its own 256-bit secret (stored hashed on the platform; `0600` file on the server).
3. Every request: `Authorization: Agent <id>:<secret>` on a **separate security chain**. User tokens
   do not work on agent endpoints and agent credentials do not work on user endpoints.
4. Credentials rotate automatically every 30 days with a 10-minute grace period.
5. Revocation takes effect on the next request.
6. Hostname mismatch between the registered server and the agent is shown as a warning.

All of it is audited: `AGENT_ENROLLMENT_TOKEN_CREATED`, `AGENT_ENROLLED`, `AGENT_ENROLLMENT_FAILED`,
`AGENT_REVOKED`, `AGENT_CREDENTIALS_ROTATED`, `AGENT_CONFIG_UPDATED`.

### 1.3 Storage (migration `V2__agents_and_metrics.sql`)

| Table | Purpose |
|---|---|
| `agents` | one per server: status, secret hash (+ previous during grace), reported OS, last seen, error count |
| `agent_enrollment_tokens` | one-time tokens (hash only) |
| `server_agent_configs` | report interval and monitored services per server |
| `server_snapshots` | latest full report per server (JSONB) — current view of interfaces, disks, services, processes |
| `metric_samples` | history, one row per (server, metric, resource, time); raw for 7 days |
| `metric_rollups_5m` | 5-minute avg/min/max; 90 days |

**Schema decision:** the architecture draft listed separate `network_interfaces`,
`service_statuses` and `process_snapshots` tables. They were replaced by the latest snapshot
(JSONB) plus the metric history: the current lists are always read together for one server, and
anything that needs history (traffic, disk usage, …) is in `metric_samples`. Fewer tables, no
duplicated data. Separate tables can be added if later phases need to query them across servers.

Ranges up to 24 hours use raw samples; longer ranges use 5-minute averages. A scheduled job builds
rollups every 5 minutes and enforces retention hourly, deleting whole 5-minute buckets only.
At 30-second reports, one server writes about 30 rows per report (~86 000 rows/day). For many
hundreds of servers, monthly partitioning of `metric_samples` is the next step (see ARCHITECTURE.md §5).

### 1.4 API

| Method & path | Who |
|---|---|
| `POST /api/v1/agent/enroll`, `/report`, `/credentials/rotate` | agents (see AGENT-PROTOCOL.md) |
| `GET /api/v1/agents`, `GET /api/v1/servers/{id}/agent` | signed in |
| `POST /api/v1/servers/{id}/agent/enrollment-token`, `/revoke`, `PUT …/agent/config` | SUPER_ADMIN, INFRASTRUCTURE_ADMIN |
| `GET /api/v1/servers/{id}/metrics?metric=…&resource=…&from=…&to=…` | signed in |
| `GET /api/v1/servers/{id}/metrics/latest`, `/interfaces`, `/services`, `/processes`, `/disks` | signed in |
| `GET /api/v1/monitoring/fleet` | signed in |

### 1.5 Web UI (English, Pashto, Dari)

- **Server page** now has tabs: Overview (live CPU, memory, fullest disk, traffic, uptime),
  Metrics (time-range picker 5 min … 7 days or custom; CPU, memory/swap, load, disk usage, traffic,
  disk I/O, errors/drops), Network, Storage, Processes & services, Agent (enrollment token with
  install command, revoke, interval and services, collector status).
- New pages: **Agents**, **CPU & memory**, **Disks**, **Network interfaces**, **Processes & services**.
- **Dashboard**: live average/highest CPU and memory, fullest disk, total traffic (loopback excluded).
- Every value shows how fresh it is (**Live** / **Stale** + "collected 12 s ago"). Charts break where
  data is missing instead of drawing a line across the gap.
- 217 new texts in each language; numbers with units stay readable in right-to-left text.

## 2. How to verify

1. Start the platform (`docker compose up -d --build`) and sign in.
2. Register a server, open it → **Agent** tab → **Create enrollment token**.
3. On an Ubuntu machine: follow `docs/AGENT-INSTALL.md` (or, to try quickly on the same machine:
   `java -jar agent/target/netmon-agent.jar enroll --url http://localhost:8088 --token <TOKEN> --config ./lab.properties`
   with `state.dir=./agent-state` in `lab.properties`, then `… run --config ./lab.properties`).
4. Within one interval the server shows **Online / Active / Healthy**; the Overview tab shows live values.
5. Metrics tab: charts fill in. Stop the platform for a minute and start it again: no gap appears.
6. Agent tab: add `ssh.service`, save; the Processes & services tab shows its state with the next report.
7. Revoke: the server shows **No agent** and the agent log says it was revoked.

## 3. Limitations (phase 2)

- **Windows agent:** phase 3. The jar refuses to run on non-Linux systems with `UNSUPPORTED`.
- **systemd unit and install script** were syntax-checked but could not be run under systemd in the
  build sandbox (no systemd there). Please test `install-agent.sh` on a lab Ubuntu server first.
  For the same reason the service collector was only tested with a simulated `systemctl`; in the
  sandbox it correctly reported `Unknown`.
- **Agent jar distribution:** built from source (`agent/mvnw package`) and copied to servers. A signed
  package (`.deb`) or a download from the platform can be added in phase 9.
- **TLS:** the agent requires HTTPS (except `localhost`). Mutual TLS per agent is planned for phase 9.
- **Rate limiting / scheduler:** in-memory, single backend instance (as in phase 1).
- **Disconnection events and alerts** (e.g. "server offline for 5 minutes") come with events and alerts in phase 5.
  Today offline servers are visible as **Offline** on the dashboard, server list and agents page.
- CPU percentages of processes are per core (a busy multi-threaded process can exceed 100 %), as in `top`.
