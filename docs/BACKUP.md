# Backup and restore

`deploy/backup/backup.sh` and `deploy/backup/restore.sh` back up and restore everything NetMon
stores:
- the PostgreSQL database: users, servers, history, events, alerts, rules, audit log, agent
  credentials (hashed);
- the packet capture files.

`.env` is not included because it holds the secrets: keep it in the ministry's secret store.

Both scripts were tested in Phase 9 against a live database, with the results below.

## 1. What a backup contains
```
/var/backups/netmon/netmon-20261102T030000Z/
  MANIFEST            created time, database schema version, encrypted yes/no
  SHA256SUMS          checksums of the files below
  database.dump.enc   pg_dump custom format (".enc" = AES-256 encrypted)
  captures.tar.enc    capture files
```
The dump is checked with `pg_restore --list` before the backup counts as done. A failed run removes
its partial directory.

## 2. Scheduling
Create a passphrase once and keep a copy OFF the platform host (it is needed for any restore):
```bash
sudo sh -c 'umask 077; openssl rand -base64 48 > /root/netmon-backup.key'
```
Daily backup at 02:30, keeping 14 days (root crontab, `crontab -e`):
```
30 2 * * * /opt/netmon/deploy/backup/backup.sh --dir /var/backups/netmon --keep-days 14 --passphrase-file /root/netmon-backup.key >> /var/log/netmon-backup.log 2>&1
```
Copy `/var/backups/netmon` to a second location (another server or the ministry's backup system)
every day. A backup on the same disk does not survive a disk failure.

Options:
- `--no-captures`: database only (captures can be large).
- `--project NAME`: when Compose runs under a project name other than `netmon`.
- `--db-container`, `--captures-volume` or `--captures-dir`: for installations without Compose.

## 3. Restore
```bash
sudo /opt/netmon/deploy/backup/restore.sh --from /var/backups/netmon/netmon-20261102T023000Z --passphrase-file /root/netmon-backup.key
```
The script then:
1. checks the checksums, and refuses a damaged or altered backup;
2. decrypts the files;
3. asks you to type `RESTORE`;
4. stops the backend and frontend;
5. restores the database in a single transaction;
6. replaces the capture files;
7. starts the services again.

Rules:
- **Same release or newer:** a backup can be restored into the release that made it. If you need a
  newer release, upgrade after the restore: the database schema is upgraded at start-up. Restoring
  an older backup over a newer schema is refused.
- **Agents** keep working after a restore: their credentials are in the database. An agent enrolled
  after the backup was taken must be enrolled again.
- **Firewall rules** applied after the backup are still on the servers but unknown to the restored
  platform. The agent reports them as "unknown NetMon rule"; remove them with the recovery commands in
  FIREWALL.md, or re-request them.

## 4. Test results (Phase 9)
| Test | Result |
|---|---|
| Encrypted backup of a live database (users, servers, applications, rules, audit) plus capture files | Written and verified |
| Damage the data (delete an application, rename a server, delete capture files), then restore | Everything restored exactly; the audit-log protection is active again |
| One byte appended to the backup | Refused: "Checksum mismatch … Not restoring" |
| Wrong passphrase | Refused: "bad decrypt"; nothing changed |

## 5. Restore drill
Restore the latest backup into a separate test machine every three months. Then sign in, open a
server's history and the audit log, and note the time it took. That time is your real recovery time.
