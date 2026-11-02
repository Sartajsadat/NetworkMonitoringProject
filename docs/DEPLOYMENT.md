# Production deployment

This guide installs NetMon on an Ubuntu server with Docker Compose behind a TLS proxy. For a
development setup see README.md; for agents see AGENT-INSTALL.md and AGENT-INSTALL-WINDOWS.md.

## 1. Sizing (measured, see PHASE-9.md)

Measured on a 4-core test machine with 100 simulated agents reporting every 10 seconds, which is
the load of 300 servers at the normal 30 seconds:
- the backend used about a quarter of one CPU core and 0.7 GB of memory;
- a report was stored in 16 ms (median; 30 ms at the 95th percentile);
- the dashboard answered in 78 ms (median) under that load.

Storage, also measured: about 15 values per report, roughly 170 bytes each including indexes.

| Data (defaults) | Per 100 servers |
|---|---|
| Raw metrics, 7 days at 30 s | about 5 GB |
| 5-minute averages, 90 days | about 6.5 GB |
| Events, alerts, audit, inventory | under 1 GB in normal use |
| Packet captures | up to `CAPTURE_STORAGE_QUOTA_BYTES` (10 GB default) |
| Flow records (if used) | depends on the network: check *Retention & storage* after a day |

| Fleet | CPU | RAM | Disk |
|---|---|---|---|
| up to 100 servers | 4 cores | 8 GB | 50 GB + capture quota |
| up to 300 servers | 4 cores | 16 GB | 100 GB + capture quota |
| more | Move the database to its own host, shorten raw-metric retention or consider partitioning (KNOWN-LIMITATIONS.md) | | |

## 2. Host preparation
```bash
sudo apt update && sudo apt install -y docker.io docker-compose-v2 nginx chrony
```
```bash
sudo systemctl enable --now docker chrony
```
- Time: `chronyc tracking` must show a synchronised clock. Agents are refused if their clock is
  more than 5 minutes off.
- Host firewall (UFW example):
  ```bash
  sudo ufw default deny incoming
  sudo ufw allow from <management-network> to any port 22 proto tcp
  sudo ufw allow 443/tcp
  sudo ufw enable
  ```
  Only if the flow collector is used: `sudo ufw allow from <exporter> to any port 2055,4739 proto udp`.

## 3. Install
```bash
sudo mkdir -p /opt/netmon && sudo chown $USER /opt/netmon
git clone <repository> /opt/netmon && cd /opt/netmon
cp .env.example .env && chmod 600 .env
```
Edit `.env`:
- `DB_PASSWORD` and `JWT_SECRET`: generate them with `openssl rand -base64 24` and
  `openssl rand -base64 48`.
- `BOOTSTRAP_SETUP_TOKEN`: `openssl rand -hex 24`. Remove it after the first sign-in.
- `AGENT_PUBLIC_URL=https://netmon.example.gov.af`: shown in the agent install commands.
- `FIREWALL_PROTECTED_CIDRS`: the management, monitoring and jump-host networks.
- Keep `HTTP_BIND=127.0.0.1`, `REFRESH_COOKIE_SECURE=true` and `API_DOCS_ENABLED=false`.

Then start:
```bash
docker compose up -d --build
```
```bash
docker compose ps    # db, backend, frontend must be "healthy"
```

## 4. TLS
1. Put the certificate and key in `/etc/netmon/tls/`: `netmon.crt` with the intermediates, and
   `netmon.key` with mode 600. The certificate must name the host that browsers and agents use.
2. Install the proxy configuration:
   ```bash
   sudo cp deploy/tls/netmon-tls.conf /etc/nginx/sites-available/netmon
   sudo ln -s /etc/nginx/sites-available/netmon /etc/nginx/sites-enabled/ && sudo rm -f /etc/nginx/sites-enabled/default
   sudo sed -i 's/netmon.example.gov.af/<your host name>/' /etc/nginx/sites-available/netmon
   sudo nginx -t && sudo systemctl reload nginx
   ```
3. Open `https://<host>`, enter the setup token and create the first super administrator.

The configuration allows TLS 1.2 and 1.3 only, sends HSTS, redirects HTTP to HTTPS, and passes the
real client address to the platform (the audit log and rate limits use it).

Tested in Phase 9 with a lab CA:
- a valid certificate works over HTTP/2;
- an untrusted certificate or a wrong host name is refused;
- TLS 1.1 is refused;
- agents connect with `--ca` and are refused without it.

If the certificate comes from the ministry's own CA, install agents with `--ca ministry-ca.pem`.

## 5. Monitoring the platform itself
- **Platform health** page (super and infrastructure administrators): database, disk, capture quota,
  agents reporting, retention runs, flow collector, clock difference, and settings that weaken
  security.
- **Alert rule "Platform disk almost full"** (default 90%) for the disk holding capture files.
  Rules for the platform server's own CPU, memory and other disks come from installing a NetMon
  agent on the platform host, like any other server (recommended).
- **Container health:** `docker compose ps`; `/actuator/health` through the proxy for an external
  monitor.
- **Logs:** `docker compose logs backend`. Docker rotates them (3 × 20 MB per service). For
  structured logs set `LOG_FORMAT=ecs`.

## 6. Backups
See [BACKUP.md](BACKUP.md): daily encrypted backup from cron, a copy off the host, and a quarterly
restore drill.

## 7. Upgrade and rollback
1. Read the release notes. Take a backup:
   ```bash
   sudo deploy/backup/backup.sh --passphrase-file /root/netmon-backup.key
   ```
2. Update and rebuild:
   ```bash
   git fetch && git checkout <release-tag>
   docker compose build --pull && docker compose up -d
   ```
   Database migrations run automatically at start-up; the backend log shows each one
   ("Migrating schema … to version N").
3. Check `docker compose ps`, the Platform health page, and that agents are still reporting.
4. **Rollback:** migrations only go forward. To go back:
   ```bash
   git checkout <previous-tag> && docker compose build && docker compose stop backend frontend
   ```
   Then restore the backup from step 1 with `restore.sh` and start the services. Data recorded since
   the backup is lost, which is why step 1 comes first.
5. **Agents:** upgrade them after the platform (newer platforms accept older agents). On each server,
   run `install-agent.sh` with the new jar; the credential is kept.

## 8. Retention
Defaults (changeable on *Retention & storage*):

| Data | Default |
|---|---|
| Raw metrics | 7 days |
| 5-minute averages | 90 days |
| Events | 180 days |
| Resolved alerts | 365 days |
| Flow records | 14 days |
| Capture files | at most 90 days |
| Ended firewall rules | 2 years |
| Audit log | 3 years (never less than 1 year) |

Shorter retention means less disk; check the ministry's record-keeping rules before shortening the
audit log.
