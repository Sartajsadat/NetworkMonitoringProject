# Architecture

Government Infrastructure Monitoring, Packet Analysis and IP Access Control Platform
(Ministry of Telecommunication).

This document answers the six questions the project brief asks for before any code:

1. Proposed architecture
2. Project folder structure
3. Key components and their responsibilities
4. Development phases and dependencies
5. Initial database design
6. Security boundaries (web app ↔ agents ↔ capture engine ↔ firewall adapters)

Sections marked **(Phase N)** describe components that are designed now but built later.
Nothing in a later phase is shown in the UI as working until it is implemented and tested.

---

## 1. Proposed architecture

```
                         ┌──────────────────────────────────────────────┐
  Browser (React/Vite)   │           Central platform (Docker)           │
  ─────────────────────  │                                              │
  Bootstrap 5 UI   HTTPS │  ┌───────────┐   ┌────────────────────────┐  │
  Axios + WS/SSE ───────►│  │  nginx    │──►│ Spring Boot backend     │  │
                         │  │ (static + │   │  /api/v1 (REST)         │  │
                         │  │  reverse  │   │  /ws (live updates, P2) │  │
                         │  │  proxy)   │   │  Spring Security + JWT  │  │
                         │  └───────────┘   │  Flyway / JPA           │  │
                         │                  └──────┬─────────┬────────┘  │
                         │                         │         │           │
                         │                ┌────────▼──┐  ┌───▼────────┐  │
                         │                │PostgreSQL │  │ Capture    │  │
                         │                │(metadata, │  │ file store │  │
                         │                │ metrics,  │  │ (PCAPNG,   │  │
                         │                │ audit)    │  │ log archive│  │
                         │                └───────────┘  └────────────┘  │
                         └──────────────────────▲───────────────────────┘
                                                │ outbound mTLS/TLS + per-agent credential
                     ┌──────────────────────────┼──────────────────────────┐
                     │                          │                          │
           ┌─────────┴────────┐      ┌──────────┴────────┐      ┌──────────┴─────────┐
           │ Linux agent (P2) │      │ Windows agent (P3)│      │ Capture sensor (P4)│
           │ systemd service  │      │ Windows service   │      │ SPAN/TAP port      │
           │ ├ metrics adapter│      │ ├ metrics adapter │      │ dumpcap + tshark   │
           │ ├ capture helper │      │ ├ Npcap capture   │      └────────────────────┘
           │ │  (dumpcap)     │      │ └ WDF firewall    │
           │ └ nftables/UFW   │      │   adapter (P7)    │      Flow / firewall telemetry
           │   adapter (P7)   │      └───────────────────┘      (NetFlow/IPFIX, syslog) (P6)
           └──────────────────┘
```

Key decisions:

| Decision | Choice | Why |
|---|---|---|
| Backend | Java 21, Spring Boot 3.5.x, Maven | Required stack; 3.5 is the stable 3.x line with long support. |
| API style | REST under `/api/v1`, DTOs only, OpenAPI via springdoc | Versioned, documented, entities never leak. |
| Auth | Short-lived JWT access token (15 min, in browser memory) + opaque refresh token (httpOnly cookie, stored hashed, rotated on each use) | Revocable sessions without a token blacklist; nothing long-lived readable by JavaScript. |
| Database | PostgreSQL 16 + Flyway | Required. Time-series volumes are handled by indexes + retention first; partitioning only when measured volume needs it (see §5). |
| Packet files | Filesystem/object-store directory, never DB rows | PCAPs are large and sensitive; DB keeps only metadata and summaries. |
| Agents | Separate processes on monitored hosts, **outbound** connection to central platform | No inbound ports on monitored servers; no remote shell. |
| Capture | dumpcap (capture) + tshark (dissection) behind a fixed-argument wrapper | Supported tools, no home-made capture engine, no shell strings. |
| Firewall | Adapter interface with a **dry-run** implementation first; real nftables/UFW/Windows Defender Firewall adapters only after lab tests | Never report a rule as applied unless verified on the target. |
| Frontend | React 18 + Vite, JavaScript/JSX, Bootstrap 5, React Router, Axios, Recharts | Required stack; no Tailwind. |
| Deployment | Docker Compose for central platform; systemd / Windows service for agents | Agents need host access and must not run in the central containers. |

## 2. Project folder structure

```
NetworkMonitoringProject/
├── README.md                     Quick start (Windows + Ubuntu)
├── .env.example                  Placeholders only, never real secrets
├── docker-compose.yml            postgres + backend + frontend (nginx)
├── docs/
│   ├── ARCHITECTURE.md           This document
│   ├── PHASE-1.md                What Phase 1 delivered and how to verify it
│   └── (later) SECURITY.md, AGENT-INSTALL.md, CAPTURE-TROUBLESHOOTING.md,
│       FIREWALL-RECOVERY.md, BACKUP-RESTORE.md, KNOWN-LIMITATIONS.md
├── backend/                      Spring Boot (Maven)
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/
│       ├── main/java/gov/mot/netmon/
│       │   ├── NetmonApplication.java
│       │   ├── common/           errors, paging, validation, request-id filter
│       │   ├── config/           security, OpenAPI, CORS, properties
│       │   ├── security/         JWT, current-user, rate limiting
│       │   ├── auth/             login / refresh / logout / me / first-run setup
│       │   ├── user/             users + roles
│       │   ├── server/           server inventory + server groups
│       │   ├── audit/            audit trail
│       │   ├── dashboard/        summary endpoint
│       │   └── (later) agent/, metrics/, capture/, network/, firewall/,
│       │       appaccess/, event/, log/, alert/, retention/
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── db/migration/     Flyway V1__, V2__, ...
│       └── test/java/...         Testcontainers (real PostgreSQL) tests
├── frontend/                     React + Vite (JavaScript)
│   ├── Dockerfile, nginx.conf
│   ├── package.json, vite.config.js
│   └── src/
│       ├── api/                  axios client + per-module API functions
│       ├── auth/                 AuthContext, ProtectedRoute, role helpers
│       ├── components/           Layout, Sidebar, TopBar, Breadcrumbs, StatusBadge, ...
│       ├── pages/                one folder/file per page
│       └── test/                 Vitest + Testing Library
├── agent/                        (Phase 2/3) Java agent: core + linux + windows adapters
└── deploy/
    ├── systemd/                  (Phase 2) agent unit files
    └── windows/                  (Phase 3) service install scripts
```

## 3. Key components and responsibilities

| Component | Responsibility | Must NOT do |
|---|---|---|
| **Frontend** | Show data, collect input, hide controls the user's roles cannot use (cosmetic only). | Hold privileged credentials; be the place where authorization is enforced. |
| **Backend API** | AuthN/AuthZ on every endpoint, validation, business rules, approvals, audit, persistence. | Run shell commands; capture packets itself; run as root. |
| **PostgreSQL** | Users, inventory, metrics samples, packet *metadata*, events, alerts, audit. | Store raw packet payloads or full PCAPs. |
| **Capture file store** | PCAP/PCAPNG files with retention and quotas, readable only by backend service account. | Be served directly by nginx. |
| **Agent (P2/P3)** | Heartbeat, metrics, interface counters, services, run *whitelisted* tasks (start capture, apply verified firewall rule). | Expose a shell; accept arbitrary commands; capture by default. |
| **Capture helper (P4)** | Fixed executable path to dumpcap/tshark, argument arrays (no shell), interface allowlist, size/duration limits, cleanup. | Accept raw command strings. |
| **Firewall adapters (P7)** | Translate an *approved* policy into nftables/UFW/Windows Defender Firewall rules, then read back to verify. | Report success without verification; mix firewall managers. |
| **Audit service** | Append-only record of sensitive actions (DB trigger forbids UPDATE/DELETE). | Store passwords, tokens, packet contents. |

## 4. Development phases and dependencies

```
P1 Foundation ──► P2 Linux agent ──► P3 Windows agent
     │                 │
     │                 ├──► P4 Packet analyzer (needs agent channel + capture helper)
     │                 └──► P5 Events & alerts (needs metrics)
     │                               │
     └──────────────────────────────►├──► P6 IP inventory & traffic analysis (needs P4 data)
                                     └──► P7 Firewall & access control (needs agents, approvals, audit)
                                                 └──► P8 Application access control
P9 Production hardening runs across everything at the end.
```

| Phase | Delivers | Depends on |
|---|---|---|
| 1 | Backend + frontend skeleton, auth, roles, users, first-run admin, server inventory & groups, audit log, Docker Compose, README | – |
| 2 | Linux agent (systemd), agent registration & credentials, heartbeat, CPU/mem/disk/net/uptime/services, live & historical charts | 1 |
| 3 | Windows agent (Windows service) using supported APIs, same backend contract | 2 |
| 4 | Capture lifecycle, dumpcap/tshark integration, packet table, details tree, display filters, conversations, history, download, retention | 2 |
| 5 | Event ingestion & correlation, timeline, alert rules, acknowledgement | 2 |
| 6 | IP/subnet inventory, flow/firewall telemetry ingestion, top talkers | 4 |
| 7 | Dry-run firewall adapter, request/approve workflow, Linux & Windows firewall adapters, verification, expiry, recovery | 2, 5 |
| 8 | Application-specific network access policies | 7 |
| 9 | Hardening, backups, retention enforcement, TLS & deployment docs, security/perf tests | all |

## 5. Initial database design

Phase 1 creates only the tables Phase 1 uses (the brief says not to create unused tables).
Later phases add tables through new Flyway migrations.

**Phase 1 tables**

```
roles (id, code UNIQUE, name, description)
users (id UUID PK, username UNIQUE ci, email UNIQUE ci, full_name, password_hash,
       active, failed_login_count, locked_until, token_version,
       last_login_at, password_changed_at, created_at, updated_at)
user_roles (user_id FK → users, role_id FK → roles, PK(user_id, role_id))
refresh_tokens (id UUID, user_id FK, token_hash UNIQUE, expires_at, revoked_at,
                replaced_by, created_at, created_ip, user_agent)
servers (id UUID, name UNIQUE, hostname, os_family, os_version, environment,
         organization, purpose, location, monitoring_status, agent_version,
         agent_health, last_seen_at, notes, created_at, updated_at, created_by, updated_by)
server_ip_addresses (id, server_id FK, ip_address, ip_version, label, UNIQUE(server_id, ip_address))
server_groups (id UUID, name, group_type, description, UNIQUE(group_type, name))
server_group_members (group_id FK, server_id FK, PK(group_id, server_id))
audit_logs (id BIGSERIAL, occurred_at, actor_user_id, actor_username, actor_roles,
            action, target_type, target_id, result, source_ip, request_id,
            reason, details JSONB)   -- UPDATE/DELETE blocked by trigger
system_settings (key PK, value, updated_at)   -- holds "setup completed" marker
```

**Phase 2 tables (done)**: `agents`, `agent_enrollment_tokens`, `server_agent_configs`,
`server_snapshots`, `metric_samples`, `metric_rollups_5m` — see PHASE-2.md for why the latest
snapshot replaced separate interface/service/process tables.

**Phase 4 tables (done)**:
- `packet_captures` — the request, limits, status and retention;
- `capture_segments` — one row per uploaded PCAPNG piece;
- `agent_tasks` — typed START/STOP tasks only;
- `packet_summaries` — per-packet metadata only, never payload.

PCAPNG files are on disk under `CAPTURE_STORAGE_DIR`, not in the database. See PHASE-4.md.

**Phase 5 tables (done)**: `events` (the timeline; correlation id, links to alerts and
captures), `alert_rules`, `alerts` (deduplicated: one active row per rule and resource). See PHASE-5.md.

**Phase 6 tables (done)**: `subnets`, `network_devices` (with flow-exporter counters), `ip_addresses`
(passively learned, with sources and first/last observed), `network_flows` (INET addresses). See PHASE-6.md.

**Planned tables (later phases)**: `log_entries` (P5b);
`firewall_integrations`, `firewall_rules`, `access_policy_requests`,
`access_policy_approvals` (P7); `application_inventory`, `application_access_policies` (P8);
`retention_policies`, `notification_settings` (P5/P9).

**Time-series strategy**: `metric_samples` will be a narrow table indexed on
`(server_id, metric, collected_at DESC)` with a retention job deleting/rolling up old rows.
Native PostgreSQL range partitioning by month is the next step only if row counts make
retention deletes or index size a problem (trade-off: partitions make retention a cheap
`DROP`, but complicate migrations and FK constraints). TimescaleDB is an option only if the
ministry approves the extension.

All timestamps are `timestamptz` and stored in UTC.

## 6. Security boundaries

```
 [Browser] ──(1) HTTPS, JWT──► [Backend] ──(2) JDBC, least-priv DB user──► [PostgreSQL]
                                   ▲
                                   │ (3) TLS, per-agent credential, outbound from agent
                                   │
                              [Agent, unprivileged user]
                                   │ (4) local, fixed argv, allowlisted interfaces
                                   ├──► [Capture helper: dumpcap with CAP_NET_RAW/CAP_NET_ADMIN only]
                                   │ (5) local, approved + verified rules only
                                   └──► [Firewall adapter: nftables / UFW / Windows Defender Firewall]
```

1. **Browser → Backend**: every privileged endpoint is checked server-side by role
   (`@PreAuthorize`). The UI hides buttons but that is never the security control.
   Access tokens live in memory; refresh token is an httpOnly, SameSite=Strict cookie
   scoped to `/api/v1/auth`.
2. **Backend → Database**: application connects with a non-superuser account; Flyway can run
   with a separate migration account in production.
3. **Agent → Backend** (Phase 2): agents open outbound connections; each agent has its own
   credential that can be rotated or revoked; the backend never pushes shell commands, only
   typed, validated tasks.
4. **Agent → Capture helper** (Phase 4, built): the only privileged piece is dumpcap.
   - Linux: file capabilities `cap_net_raw,cap_net_admin`, executable only by the `wireshark`
     group, granted through a systemd drop-in. The agent's Java process keeps no capabilities.
   - Windows: the Npcap driver.
   - Capture is switched on locally by the server's administrator, never by the platform.
   - The agent re-checks every request against its local allowlist and limits.
   - The backend analyses the files with TShark, without privileges.
5. **Agent → Firewall** (Phase 7): rules must be requested, approved, previewed (including
   "would this block the management connection?" check), applied, then read back. Status is
   `PENDING`, `APPLIED`, `FAILED`, `EXPIRED` or `UNVERIFIED` — never "success" because a call
   was sent. Dry-run is the default.
