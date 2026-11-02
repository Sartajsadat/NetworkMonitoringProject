# Security configuration and review

NetMon is a privileged government system: it sees server activity and network traffic, and can
change firewalls. This guide lists the controls, what to configure, and how to respond to incidents.

## 1. Controls in place (reviewed in Phase 9)

| Area | Control | Where |
|---|---|---|
| Transport | TLS 1.2/1.3 at the TLS proxy, HSTS, HTTP redirects only; agents refuse plain HTTP except `localhost` or an explicit lab switch | `deploy/tls/netmon-tls.conf`, agent `platform.url` |
| User sign-in | BCrypt-hashed passwords (cost 12) with a strength policy, account lockout (5 failures / 15 min), per-IP sign-in rate limit, short access tokens (15 min), refresh token in an HttpOnly, Secure, SameSite=Strict cookie, rotation with reuse detection | backend `auth`, `security` |
| Roles | Five roles; every endpoint is authorised on the server (`@PreAuthorize`); UI hides what a role cannot do. Four-eyes approval for firewall and application policies | PHASE-1.md, FIREWALL.md |
| Agents | One-time enrolment tokens (24 h), per-agent random secret stored hashed, rotation every 30 days with a grace period, revocation; agent credentials are accepted only on `/api/v1/agent/**` and user tokens never there | AGENT-PROTOCOL.md |
| No remote commands | Agents only accept typed tasks (capture, firewall rule) with strictly validated fields; fixed executables with argument lists, no shell; local opt-in switches the platform cannot override | agent `CommandRunner`, `FwRule`, `AppPolicy` |
| Least privilege | Backend and frontend containers run as non-root with all Linux capabilities dropped and `no-new-privileges`; the agent runs as an unprivileged user; packet capture and firewall control use narrow file capabilities on dedicated binaries | `docker-compose.yml`, `deploy/` |
| Input | Bean validation on every request body; strict CIDR / port / identity parsing; parameterised SQL only; 2 MB request limit (70 MB only for capture pieces) | backend, `frontend/nginx.conf` |
| Browser | CSP without inline scripts, `frame-ancestors 'none'`, `X-Frame-Options DENY`, `nosniff`, `no-referrer`, `no-store` on API responses; CORS off unless origins are listed | `SecurityConfig`, `nginx.conf` |
| Errors | Central handler: clean 4xx/5xx JSON with a request ID, never stack traces or SQL | `GlobalExceptionHandler` |
| Audit | Append-only audit log (database trigger); entries younger than one year cannot be deleted even by the application's database account; passwords, tokens and packet contents are never logged | V1, V8 migrations |
| Data | Packet payloads only in capture files with role-restricted, one-time download tickets; retention for every data type (UI); backups checksummed and optionally AES-256 encrypted | PACKET-CAPTURE.md, BACKUP.md |
| Management endpoints | Only `/actuator/health` and `/actuator/info`; `/actuator/*` blocked at nginx; API docs off in Compose (`API_DOCS_ENABLED=false`) | `application.yml`, `nginx.conf` |

### Automated security tests
`SecurityHardeningTest` discovers every API endpoint from the code and checks:
- every non-public endpoint (more than 100) answers 401 without a sign-in;
- a read-only user cannot make any change (only their own password and session);
- user and agent credentials cannot be swapped;
- security headers are present;
- malformed input and injection attempts give clean 4xx errors, never 500 or internal details;
- the actuator exposes nothing beyond health;
- cross-origin requests from unknown origins are refused.

A new endpoint that forgets authorisation fails this test. The run in Phase 9 found one defect, now
fixed: a wrong `Content-Type` on the CSV import returned 500 instead of 415. `npm audit` reported
0 vulnerabilities in the frontend dependencies.

## 2. What you must configure

1. **Secrets in `.env` only** (never in Git): `DB_PASSWORD` (24+ random characters), `JWT_SECRET`
   (48+ random bytes, base64). Keep a copy in the ministry's secret store. `chmod 600 .env`.
2. **TLS:** install the TLS proxy with a certificate from the ministry CA (or a public CA). Keep
   `REFRESH_COOKIE_SECURE=true`. The platform health page warns if it is off.
3. **Bind addresses:** keep `HTTP_BIND=127.0.0.1` (only the TLS proxy reaches the UI) and set
   `FLOW_BIND` to the management-network address if the flow collector is used.
4. **Host firewall of the platform server:** allow 443 from user and server networks, UDP 2055/4739
   only from the registered exporters, SSH only from the management network. Deny everything else.
5. **Protected addresses:** set `FIREWALL_PROTECTED_CIDRS` to the management, monitoring and jump-host
   networks before any firewall target is set to *Enforce*.
6. **First administrator:** use the one-time setup token, then remove `BOOTSTRAP_SETUP_TOKEN` from `.env`.
   Create personal accounts; do not share the super administrator.
7. **Time:** run NTP on the platform host and all monitored servers (reports from clocks more than
   5 minutes off are refused; correlation depends on time).
8. **Backups:** schedule `deploy/backup/backup.sh` with `--passphrase-file`; store the passphrase
   off the host; test a restore every quarter (BACKUP.md).
9. **Updates:** apply OS and Docker image updates monthly; rebuild images (`docker compose build --pull`).
   Run `npm audit` and a dependency scanner (e.g. OWASP Dependency-Check) in CI.

### Optional: separate database roles
Flyway needs DDL rights at start-up; the running application only needs DML. For stricter separation,
run migrations once with an owner account (`DB_USERNAME=netmon_owner`), then run the application
with a role that has `SELECT, INSERT, UPDATE, DELETE` on the tables and `USAGE` on sequences:
```sql
CREATE ROLE netmon_app LOGIN PASSWORD '…';
GRANT USAGE ON SCHEMA public TO netmon_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO netmon_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO netmon_app;
GRANT EXECUTE ON FUNCTION purge_audit_logs(timestamptz) TO netmon_app;
```
and set `SPRING_FLYWAY_ENABLED=false` for the application (enable it only during upgrades, with the
owner account).

## 3. Credential rotation

| Credential | How |
|---|---|
| User passwords | Users change their own; administrators can reset (audited). Lockout after 5 failures |
| `JWT_SECRET` | Change in `.env`, `docker compose up -d backend`. All users must sign in again; agents are not affected |
| `DB_PASSWORD` | `ALTER USER netmon PASSWORD '…'` in the database, then update `.env` and restart the backend |
| Agent secrets | Rotate automatically every 30 days; revoke a server's agent on its page (immediately rejected) and re-enrol |
| Backup passphrase | New backups use the new file; keep old passphrases as long as old backups are kept |
| TLS certificate | Replace the files, `nginx -t && systemctl reload nginx`. Agents trusting the CA need no change |

## 4. Incident response

**A. Suspected compromise of an administrator account**
1. Deactivate the account (Users → deactivate); its sessions stop at the next token refresh (≤ 15 min).
   For immediate effect, rotate `JWT_SECRET` (signs everyone out).
2. Review the audit log for that user (filter by actor): firewall approvals, mode changes, capture
   downloads, user changes.
3. Remove any firewall rule or application policy it approved that is not justified; set targets
   back to *Dry run* if in doubt.
4. Reset the password; check the user's roles; re-enable only after review.

**B. Suspected compromise of a monitored server or its agent**
1. Revoke the agent on the server's page. The platform rejects it at once.
2. Firewall rules applied there stay until removed; review them, and use the recovery commands in
   FIREWALL.md on the server if needed.
3. Re-enrol only after the server is rebuilt or cleaned.

**C. Platform host compromise or data loss**
1. Isolate the host. Preserve logs (`docker compose logs`, `/var/log`).
2. Build a clean host, deploy the same release, restore the latest good backup (BACKUP.md).
3. Rotate `JWT_SECRET`, `DB_PASSWORD`, the backup passphrase and the TLS key; revoke and re-enrol agents.
4. Report according to the ministry's incident procedure.

**D. A firewall rule cut off access**
Follow the recovery procedure in FIREWALL.md (section 4): expiry, removal on the platform, or the
one-line console command per firewall type.
