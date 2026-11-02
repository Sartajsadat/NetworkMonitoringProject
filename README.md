# Infrastructure Monitoring & Network Management Platform

Central platform for the Ministry of Telecommunication to monitor ministry-hosted servers,
analyse network traffic, investigate incidents and (later) manage IP access rules.

> **Status: Phase 8 of 9 done (application access control).** Done so far:
> - authentication, users, server inventory and the audit log;
> - English/Pashto/Dari UI;
> - monitoring agents for Linux and Windows (live and historical CPU, memory, disk, network,
>   service and process data);
> - packet capture with dumpcap on the servers and analysis with TShark on the platform;
> - an event timeline (service changes, restarts, disconnections, captures, security events) and
>   configurable alert rules with acknowledgement;
> - subnets, an IP address inventory, network devices, and NetFlow v5/v9 / IPFIX traffic analysis;
> - IP allow/block rules with second-person approval, dry run first, and verified enforcement on
>   server firewalls (nftables, UFW, Windows Defender Firewall);
> - per-application network policies (block internet / allow only / block destinations) enforced on
>   a Linux account or a Windows program or service, with unsupported cases shown as such.
>
> The Windows agent and Windows capture still need a test on a real Windows server: see
> docs/PHASE-3.md and docs/PHASE-4.md. Centralized logs and the hosted-application inventory
> (marked "Phase 5b") arrive later and are shown in the UI as
> *not available yet*, never with placeholder numbers.

| Document | Contents |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Architecture, folder layout, phases, database design, security boundaries |
| [docs/PHASE-1.md](docs/PHASE-1.md) | What Phase 1 delivered, how it works, how to verify it, limitations |
| [docs/PHASE-2.md](docs/PHASE-2.md) | Linux monitoring agent, metrics storage, monitoring pages |
| [docs/AGENT-INSTALL.md](docs/AGENT-INSTALL.md) | Installing, enrolling and operating the Linux agent |
| [docs/PHASE-3.md](docs/PHASE-3.md) | Windows agent, and the Windows test checklist |
| [docs/AGENT-INSTALL-WINDOWS.md](docs/AGENT-INSTALL-WINDOWS.md) | Installing the Windows agent as a service |
| [docs/AGENT-PROTOCOL.md](docs/AGENT-PROTOCOL.md) | Agent ↔ platform API |
| [docs/PHASE-4.md](docs/PHASE-4.md) | Packet analyzer: how capture and analysis work, Windows test checklist |
| [docs/PACKET-CAPTURE.md](docs/PACKET-CAPTURE.md) | Enabling capture, visibility levels, filters, roles, troubleshooting |
| [docs/PHASE-5.md](docs/PHASE-5.md) | Event timeline, correlation, alert rules, acknowledgement, data gaps |
| [docs/PHASE-6.md](docs/PHASE-6.md) | Subnets, IP inventory, network devices, flow collector, traffic pages |
| [docs/NETWORK-VISIBILITY.md](docs/NETWORK-VISIBILITY.md) | Host vs mirror-port vs flow visibility; sending NetFlow/IPFIX from routers and firewalls |
| [docs/PHASE-7.md](docs/PHASE-7.md) | Firewall targets, IP allow/block workflow, real nftables test |
| [docs/PHASE-8.md](docs/PHASE-8.md) | Application access control: identities, policy types, real test |
| [docs/FIREWALL.md](docs/FIREWALL.md) | Enabling firewall control, scope, safety checks, statuses, **recovery procedure** |
| [docs/LANGUAGES-AND-DESIGN.md](docs/LANGUAGES-AND-DESIGN.md) | English / Pashto / Dari, right-to-left layout, design and translation files |

## Stack

- **Backend:** Java 21, Spring Boot 3.5, Spring Security (JWT), Spring Data JPA, Flyway, PostgreSQL 16, springdoc OpenAPI
- **Frontend:** React 18 + Vite 7 (JavaScript/JSX), Bootstrap 5 (LTR + RTL), React Router, Axios, Recharts
- **Languages:** English, پښتو (Pashto), دری (Dari) with full right-to-left layout
- **Deployment:** Docker Compose (PostgreSQL + backend + nginx-served frontend)

## Quick start with Docker (Ubuntu or Windows)

Requirements: Docker Engine 24+ with the Compose plugin (Ubuntu) or Docker Desktop (Windows).

```bash
git clone <this repository> netmon && cd netmon
cp .env.example .env
```

Edit `.env` and set real values for at least `DB_PASSWORD` and `JWT_SECRET`:

```bash
openssl rand -base64 24   # use for DB_PASSWORD
```

```bash
openssl rand -base64 48   # use for JWT_SECRET
```

On Windows without OpenSSL, generate the values in PowerShell:

```powershell
[Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Maximum 256 }))
```

If you open the UI over plain `http://` (local testing only), also set
`REFRESH_COOKIE_SECURE=false`, otherwise the browser drops the session cookie.

```bash
docker compose up -d --build
```

Open <http://localhost:8088>. The first visit shows **First-run setup**. Get the one-time
setup token from the backend log:

```bash
docker compose logs backend | grep -A6 "FIRST-RUN SETUP"
```

Enter the token and create the first super administrator. There are no default accounts.

## Development without Docker

### Prerequisites

| Tool | Version | Ubuntu | Windows |
|---|---|---|---|
| JDK | 21 | `sudo apt install openjdk-21-jdk` | [Eclipse Temurin 21](https://adoptium.net) MSI, tick "Set JAVA_HOME" |
| Node.js | 22 LTS (≥ 20.19 or 22.12) | [NodeSource](https://github.com/nodesource/distributions) or `nvm` | [nodejs.org](https://nodejs.org) installer |
| PostgreSQL | 16 | `sudo apt install postgresql-16` | [EDB installer](https://www.postgresql.org/download/windows/) or the compose `db` service |
| Docker | optional for dev, **required for backend tests** (Testcontainers) | `docker.io` / Docker CE | Docker Desktop |
| TShark | for packet analysis (and its backend tests) | `sudo apt install tshark` (answer *No* to non-root capture) | [Wireshark installer](https://www.wireshark.org/download.html), then set `TSHARK_PATH=C:\Program Files\Wireshark\tshark.exe` |

Maven does not need to be installed: use the wrapper (`./mvnw` on Ubuntu, `mvnw.cmd` on Windows).

### 1. Database

Either start only the database container:

```bash
docker compose up -d db
```

(then uncomment the `ports` line under `db` in `docker-compose.yml` to reach it from the host), or create it in a local PostgreSQL:

```sql
CREATE USER netmon WITH PASSWORD 'choose-a-password';
CREATE DATABASE netmon OWNER netmon;
```

### 2. Backend (port 8080)

Ubuntu / macOS:

```bash
cd backend
export DB_URL=jdbc:postgresql://localhost:5432/netmon DB_USERNAME=netmon DB_PASSWORD=choose-a-password
export JWT_SECRET="$(openssl rand -base64 48)" REFRESH_COOKIE_SECURE=false
./mvnw spring-boot:run
```

Windows PowerShell:

```powershell
cd backend
$env:DB_URL="jdbc:postgresql://localhost:5432/netmon"; $env:DB_USERNAME="netmon"; $env:DB_PASSWORD="choose-a-password"
$env:JWT_SECRET="<paste a base64 value of 48 random bytes>"; $env:REFRESH_COOKIE_SECURE="false"
.\mvnw.cmd spring-boot:run
```

Flyway creates the schema on start. The setup token is printed in this console.

For packet analysis without Docker, install TShark (see the prerequisites table). On Windows also
set where capture files go, for example
`$env:CAPTURE_STORAGE_DIR="C:\NetMon\captures"` and
`$env:TSHARK_PATH="C:\Program Files\Wireshark\tshark.exe"`. Without TShark the backend still starts;
the Captures page then says that analysis is not available.
Swagger UI: <http://localhost:8080/swagger-ui.html>.

### 3. Frontend (port 5173)

```bash
cd frontend
npm ci
npm run dev
```

On Windows PowerShell, if `npm` is blocked by the script execution policy, use `npm.cmd ci`
and `npm.cmd run dev`.

Open <http://localhost:5173>. Vite proxies `/api` to `http://localhost:8080`
(override with `VITE_BACKEND_URL`).

## Tests

Backend (needs a running Docker daemon; Testcontainers starts a throw-away PostgreSQL 16):

```bash
cd backend && ./mvnw test
```

Frontend:

```bash
cd frontend && npm test
```

Agent:

```bash
cd agent && ./mvnw test
```

## Monitoring agent

Build it with `cd agent && ./mvnw package` (creates `agent/target/netmon-agent.jar`). The same jar
runs on Linux and Windows. Follow [docs/AGENT-INSTALL.md](docs/AGENT-INSTALL.md) for Linux and
[docs/AGENT-INSTALL-WINDOWS.md](docs/AGENT-INSTALL-WINDOWS.md) for Windows. Packet capture is off
until enabled on each server: see [docs/PACKET-CAPTURE.md](docs/PACKET-CAPTURE.md).

## Environment variables

All configuration comes from the environment; see [.env.example](.env.example).

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `DB_URL` | backend only (compose sets it) | `jdbc:postgresql://localhost:5432/netmon` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | yes | `netmon` / – | Database account (not a superuser in production) |
| `JWT_SECRET` | **yes** | – | ≥ 32 random bytes, base64. Backend refuses to start without it |
| `ACCESS_TOKEN_TTL` | no | `PT15M` | Access token lifetime (ISO-8601) |
| `REFRESH_TOKEN_TTL` | no | `PT8H` | Session lifetime; refresh token rotates on each use |
| `REFRESH_COOKIE_SECURE` | no | `true` | Send refresh cookie only over HTTPS. `false` only for plain-HTTP local testing |
| `BOOTSTRAP_SETUP_TOKEN` | no | generated | Fixed one-time setup token (≥ 24 chars) instead of a generated one |
| `MAX_FAILED_LOGINS` / `LOCKOUT_DURATION` | no | `5` / `PT15M` | Account lockout policy |
| `LOGIN_RATE_LIMIT_PER_MINUTE` | no | `20` | Login attempts per client IP per minute |
| `CORS_ALLOWED_ORIGINS` | no | empty (off) | Only needed if UI and API are on different origins |
| `API_DOCS_ENABLED` | no | `true` (compose: `false`) | Swagger UI / OpenAPI JSON |
| `LOG_FORMAT` | no | plain | `ecs` or `logstash` for JSON logs |
| `AGENT_PUBLIC_URL` | no | browser address | Platform address shown in agent install commands |
| `AGENT_ENROLLMENT_TOKEN_TTL` | no | `P1D` | Lifetime of one-time enrollment tokens |
| `AGENT_ONLINE_THRESHOLD` | no | `PT2M` | A server is Online if its agent reported within this time |
| `METRICS_RAW_RETENTION` / `METRICS_ROLLUP_RETENTION` | no | `P7D` / `P90D` | History retention |
| `CAPTURE_STORAGE_DIR` | no | `/var/lib/netmon/captures` | Where capture files are kept (compose: volume `capture-data`) |
| `CAPTURE_STORAGE_QUOTA_BYTES` | no | 10 GB | Total space for all capture files |
| `CAPTURE_MAX_DURATION_SECONDS` / `CAPTURE_MAX_BYTES` | no | `3600` / 1 GB | Upper limits for one capture |
| `CAPTURE_DEFAULT_RETENTION_DAYS` / `CAPTURE_MAX_RETENTION_DAYS` | no | `7` / `90` | Automatic deletion of capture files |
| `TSHARK_PATH` | no | `/usr/bin/tshark` | TShark used to analyse captures (included in the Docker image) |
| `EVENTS_RETENTION` / `ALERTS_RESOLVED_RETENTION` | no | `P180D` / `P365D` | How long timeline events and resolved alerts are kept |
| `EVENTS_MAX_PER_SERVER_PER_HOUR` | no | `600` | Flood protection for the timeline |
| `ALERT_EVALUATION_INTERVAL` | no | `PT1M` | How often alert rules are evaluated |
| `FLOW_COLLECTOR_ENABLED` | no | `false` | Receive NetFlow v5/v9 and IPFIX from registered network devices |
| `FLOW_BIND` / `FLOW_NETFLOW_PORT` / `FLOW_IPFIX_PORT` | no | `127.0.0.1` / `2055` / `4739` | Where compose publishes the collector's UDP ports (use the management-network address) |
| `FLOW_MAX_PER_SECOND` / `FLOWS_RETENTION` | no | `20000` / `P14D` | Per-exporter rate limit; how long flow records are kept |
| `HTTP_BIND` / `HTTP_PORT` | no | `127.0.0.1` / `8088` | Where compose publishes the UI |

## Repository layout

```
backend/    Spring Boot API (Maven)        frontend/   React + Vite UI
agent/      Monitoring agent, Linux + Windows deploy/     Agent install scripts (Linux, Windows)
docs/       Architecture, phase notes, guides
docker-compose.yml, .env.example
```
