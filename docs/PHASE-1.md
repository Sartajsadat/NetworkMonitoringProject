# Phase 1 — Project foundation

**Goal:** a runnable, tested base: secure login, roles, user management, server inventory,
audit trail, dashboard shell, Docker deployment.

**Result:** done. 55 backend tests (against real PostgreSQL) and 29 frontend tests pass.
The full stack was also run with Docker Compose and checked end to end (setup → login →
register server → dashboard → audit log).

---

## 1. What was built, and how

### 1.1 Backend (`backend/`)

Layered as **Controller → Service → Repository → Entity**, DTOs at the API edge, one package per module:

| Package | Contents |
|---|---|
| `common` | Structured error body (`ApiError`), global exception handler, request-ID filter, safe paging/sorting, strict IP parser |
| `config` | Security filter chain, CORS, OpenAPI, typed `app.*` properties |
| `security` | JWT issue/verify, token→user converter, rate limiter, JSON 401/403 handlers |
| `auth` | Login, refresh, logout, change password, first-run setup, refresh-token storage |
| `user` | Users, roles, password policy, admin user management |
| `server` | Server inventory, IP addresses, server groups |
| `audit` | Append-only audit trail + search API |
| `dashboard` | Summary endpoint with honest module availability |

**Authentication design**

- Login returns a **15-minute JWT access token** (in the JSON body; the UI keeps it in memory only)
  and sets an **httpOnly, SameSite=Strict refresh cookie** scoped to `/api/v1/auth`.
- The refresh token is random; the database stores only its SHA-256 hash. Every refresh
  **rotates** it. If an old (already rotated) token is presented, it is treated as stolen:
  all of that user's sessions are revoked and an audit entry is written.
- The JWT carries only user id, username and a `token_version`. On every request the backend
  re-reads the user, so **deactivation, password reset and role changes apply immediately**.
- Passwords: BCrypt (cost 12). Policy: 12–128 characters, 3 of 4 character classes, must not contain the username.
- Lockout: 5 failed logins → locked 15 minutes. Per-IP rate limit on login and setup.
- Same error message for unknown user / wrong password / locked / disabled (no account enumeration).
- Refresh and logout additionally require an `X-Requested-With` header (CSRF defence in depth).

**First-run administrator (no default credentials)**

While the `users` table is empty, the backend prints a one-time setup token to **its own log**
(or uses `BOOTSTRAP_SETUP_TOKEN`). `/setup` in the UI accepts that token once to create the
first `SUPER_ADMIN`; afterwards the endpoint returns 409 permanently.

**Authorization** (enforced in the backend with `@PreAuthorize`; the UI only hides buttons)

| Action | SUPER_ADMIN | INFRASTRUCTURE_ADMIN | NETWORK_ADMIN | SECURITY_ANALYST | READ_ONLY |
|---|:-:|:-:|:-:|:-:|:-:|
| View dashboard, servers, groups | ✔ | ✔ | ✔ | ✔ | ✔ |
| Register / edit servers and groups | ✔ | ✔ | | | |
| Delete servers | ✔ | | | | |
| Manage users | ✔ | | | | |
| Read audit log | ✔ | | | ✔ | |

Guards: you cannot deactivate yourself, and the last active SUPER_ADMIN cannot be deactivated or demoted.

**Audit trail**

- `audit_logs` is **append-only**: a PostgreSQL trigger rejects `UPDATE`, `DELETE` and `TRUNCATE`.
- Entries are written in their own transaction, so failed operations are still recorded.
- Each entry has: time (UTC), user, roles, action, target, result (`SUCCESS`/`FAILURE`/`DENIED`),
  client IP (resolved through the trusted nginx proxy), request ID, reason, and structured
  details (e.g. a before/after diff for server edits). Passwords and tokens are never recorded.
- Audited now: login success/failure, logout, refresh-token reuse, password change/reset,
  setup, every access-denied, user create/update/activate/deactivate/unlock, server and
  group create/update/delete.

**Server inventory**

- Fields from the brief: name, hostname, IPs, OS + version, environment, organization,
  purpose, location, agent version, agent health, last communication, monitoring status,
  created/updated time and user.
- IPv4 and IPv6 validated strictly (never via DNS) and stored in canonical form
  (`2001:0DB8:0:0:0:0:0:1` → `2001:db8::1`). Hostnames must be valid DNS names or IPs.
- Groups by department / environment / application / server role / custom.
- `ACTIVE` monitoring status cannot be set by hand — only an agent (Phase 2) can set it.
  With no agent, connection status is `NO_AGENT` and "last seen" is `Never`.
- Filtering by text (name, hostname, IP, organization), environment, OS, organization,
  status, group; whitelisted sorting; page size capped at 200.

**Other**

- Every response carries `X-Request-Id`; it also appears in logs, audit entries and error bodies.
- Structured JSON logs available with `LOG_FORMAT=ecs`.
- `/actuator/health` is public but shows only `UP`/`DOWN`; nothing else from Actuator is exposed.
- Swagger UI at `/swagger-ui.html` (switch off with `API_DOCS_ENABLED=false`).

### 1.2 Database (`backend/src/main/resources/db/migration/V1__foundation.sql`)

Tables: `roles` (5 seeded), `users`, `user_roles`, `refresh_tokens`, `system_settings`,
`servers`, `server_ip_addresses`, `server_groups`, `server_group_members`, `audit_logs`.
UUID keys, `timestamptz` everywhere (UTC), CHECK constraints matching the Java enums,
indexes for the common filters. Hibernate runs in `validate` mode; only Flyway changes the schema.

### 1.3 Frontend (`frontend/`)

- Pages: **Login, First-run setup, Dashboard, Server inventory, Server detail,
  Register/Edit server, Server groups, Users & roles, Audit log, Change password**, plus
  403 and 404 pages.
- Reusable components: sidebar, top bar, breadcrumbs, status badges (icon + text, never
  colour only), confirm dialog, modal, pagination, loading/empty/error states (errors show the request ID).
- Sidebar lists all 26 planned pages; unbuilt ones are greyed out with their phase number.
- Dashboard: inventory tiles, servers by OS / environment bar charts, module availability
  ("Not collected" / "Not configured"), recent failed/denied actions (admins and analysts
  only), auto-refresh selector (off / 30 s / 1 min / 5 min), timestamp of the data.
- Session: access token in memory; after a page reload the app silently refreshes using the
  cookie. Concurrent 401s share one refresh call (needed because refresh tokens rotate).
- Light/dark mode follows the operating system.

### 1.4 Deployment

- `docker-compose.yml`: `db` (PostgreSQL 16, not published to the host), `backend`
  (non-root user, no host port), `frontend` (unprivileged nginx serving the UI and proxying
  `/api`; published on `127.0.0.1:8088` by default).
- nginx adds CSP, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy`, and blocks everything under `/actuator/` except `/actuator/health`.
- Secrets only via `.env` (git-ignored). `.env.example` contains placeholders.

## 2. API added in this phase

| Method & path | Who |
|---|---|
| `GET /api/v1/setup/status`, `POST /api/v1/setup/admin` | public (setup only while no users exist) |
| `POST /api/v1/auth/login`, `/refresh`, `/logout` | public |
| `GET /api/v1/auth/me`, `POST /api/v1/auth/password` | signed in |
| `GET /api/v1/roles` | signed in |
| `GET/POST /api/v1/users`, `GET/PUT /api/v1/users/{id}`, `POST /api/v1/users/{id}/activate`, `/deactivate`, `/unlock`, `/reset-password` | SUPER_ADMIN |
| `GET /api/v1/servers`, `GET /api/v1/servers/{id}`, `GET /api/v1/servers/organizations` | signed in |
| `POST /api/v1/servers`, `PUT /api/v1/servers/{id}` | SUPER_ADMIN, INFRASTRUCTURE_ADMIN |
| `DELETE /api/v1/servers/{id}` | SUPER_ADMIN |
| `GET /api/v1/server-groups` | signed in |
| `POST/PUT/DELETE /api/v1/server-groups[/{id}]` | SUPER_ADMIN, INFRASTRUCTURE_ADMIN |
| `GET /api/v1/audit-logs`, `GET /api/v1/audit-logs/actions` | SUPER_ADMIN, SECURITY_ANALYST |
| `GET /api/v1/dashboard/summary` | signed in |

Errors always use one JSON shape:
`{timestamp, status, error, code, message, path, requestId, fieldErrors[]}`.

## 3. Tests

| Suite | What it proves |
|---|---|
| `AuthApiTest` (10) | login + cookie flags, generic errors, lockout, token required, refresh rotation and theft detection, CSRF header, logout, deactivation kills tokens, password change |
| `SetupApiTest` (2) | setup token required, one-time only, password policy |
| `AuthorizationTest` (6) | role matrix above, access-denied is audited, health shows no details |
| `ServerApiTest` (7) | IPv4/IPv6 normalisation, invalid IP/hostname rejected, case-insensitive duplicate names, ACTIVE not settable, filters/sort/page limits, update diff audited, 404 with request ID |
| `UserApiTest` (5) | create, weak/duplicate/unknown role rejected, last-admin guard, reset password revokes sessions |
| `AuditTrailTest` (3) | trigger blocks UPDATE/DELETE/TRUNCATE, search filters, no passwords in audit |
| `DashboardApiTest` (2) | counts, metrics reported as NOT_COLLECTED, security events hidden from read-only |
| Unit tests (20) | IP parser, password policy, rate limiter |
| Frontend (29) | login errors + request ID, setup redirect, role-based sidebar, disabled future modules, protected routes, server table/filters/role buttons/error state, dashboard honest states, IP check, error messages |

Run: `cd backend && ./mvnw test` (needs Docker) and `cd frontend && npm test`.

## 4. How to verify by hand

1. `docker compose up -d --build`, open <http://localhost:8088> → you land on **First-run setup**.
2. Try a wrong token → "Setup token is invalid". Use the token from `docker compose logs backend` → admin created.
3. Sign in. Dashboard shows 0 servers and **Not collected** for metrics.
4. **Server groups** → create "Ministry of Health" (Department).
5. **Server inventory → Register server** with IPs `10.0.0.5` and `2001:DB8::0:5` → detail page shows `2001:db8::5`, connection **No agent**, last seen **Never**.
6. **Users & roles** → create a `READ_ONLY` user. Sign in as them in a private window: no *Register server* button, no *Users* / *Audit log* links; calling `POST /api/v1/servers` with their token returns 403.
7. As admin, deactivate that user → their open session stops working on the next click.
8. **Audit log** → all of the above is listed with user, IP and request ID; "Details" shows the server edit diff.
9. Reload the browser → you stay signed in (silent refresh). Sign out → reload → login page.

## 5. Limitations and notes (Phase 1)

- **No metrics yet.** CPU/memory/disk/network need the agent (Phase 2 Linux, Phase 3 Windows).
- **MFA is not implemented yet.** Planned with production hardening (Phase 9; TOTP for privileged roles).
- **Rate limiter is in-memory** — correct for a single backend instance. Running several backend replicas needs a shared store.
- **TLS** is not terminated by this compose file. In production put the UI behind HTTPS (ministry reverse proxy / load balancer) and keep `REFRESH_COOKIE_SECURE=true`. TLS setup is documented in Phase 9.
- On first page load before signing in, the browser console shows one `401` from the silent session check. This is expected.
- Inside restricted networks without Maven Central / npm access, `docker compose build` needs a local mirror (Maven `settings.xml` mirror and `npm config set registry`).
- Audit-log retention (deleting old rows) is deliberately impossible for the application; a separately privileged retention procedure comes in Phase 9.
