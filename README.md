# Infrastructure Monitoring & Network Management Platform

Central platform for the Ministry of Telecommunication to monitor ministry-hosted servers,
analyse network traffic, investigate incidents and (later) manage IP access rules.

> **Status: Phase 1 of 9 (foundation).** Authentication, roles, users, server inventory,
> server groups, audit log, dashboard shell and Docker deployment work today.
> Metrics, packet capture, alerts and firewall control arrive in later phases and are
> shown in the UI as *not available yet* — never with placeholder numbers.

| Document | Contents |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Architecture, folder layout, phases, database design, security boundaries |
| [docs/PHASE-1.md](docs/PHASE-1.md) | What Phase 1 delivered, how it works, how to verify it, limitations |
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
| `HTTP_BIND` / `HTTP_PORT` | no | `127.0.0.1` / `8088` | Where compose publishes the UI |

## Repository layout

```
backend/    Spring Boot API (Maven)        frontend/   React + Vite UI
docs/       Architecture and phase notes   deploy/     Agent service files (Phase 2+)
docker-compose.yml, .env.example
```
