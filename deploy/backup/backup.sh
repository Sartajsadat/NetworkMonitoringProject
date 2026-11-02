#!/usr/bin/env bash
# NetMon backup: PostgreSQL (pg_dump custom format) + packet capture files, with SHA-256 checksums
# and optional AES-256 encryption. Run on the Docker host (cron/systemd timer).
#
#   sudo ./backup.sh [--dir /var/backups/netmon] [--keep-days 14] [--passphrase-file /root/netmon-backup.key]
#                    [--no-captures] [--project netmon]
#                    [--db-container NAME] [--captures-volume NAME | --captures-dir PATH]
#
# Defaults fit the Docker Compose deployment (project "netmon": service "db", volume
# "netmon_capture-data"). .env is NOT included: keep it (it holds the secrets) in your secret store.
# Encryption: openssl AES-256-CBC with PBKDF2 (200 000 iterations); keep the passphrase file off this host.
set -euo pipefail

DIR=/var/backups/netmon KEEP=14 PASS="" CAPTURES=1 PROJECT=netmon DB_CONTAINER="" CAP_VOLUME="" CAP_DIR=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dir) DIR="$2"; shift 2 ;;
    --keep-days) KEEP="$2"; shift 2 ;;
    --passphrase-file) PASS="$2"; shift 2 ;;
    --no-captures) CAPTURES=0; shift ;;
    --project) PROJECT="$2"; shift 2 ;;
    --db-container) DB_CONTAINER="$2"; shift 2 ;;
    --captures-volume) CAP_VOLUME="$2"; shift 2 ;;
    --captures-dir) CAP_DIR="$2"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done
[[ "$KEEP" =~ ^[0-9]+$ ]] || { echo "--keep-days must be a number" >&2; exit 2; }
[[ -z "$PASS" || -r "$PASS" ]] || { echo "Cannot read passphrase file $PASS" >&2; exit 2; }

if [[ -n "$DB_CONTAINER" ]]; then DB_EXEC=(docker exec -i "$DB_CONTAINER")
else DB_EXEC=(docker compose -p "$PROJECT" exec -T db); fi
[[ -n "$CAP_VOLUME$CAP_DIR" ]] || CAP_VOLUME="${PROJECT}_capture-data"

STAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUT="$DIR/netmon-$STAMP"
umask 077
mkdir -p "$OUT"
trap 'echo "Backup FAILED; removing incomplete $OUT" >&2; rm -rf "$OUT"' ERR

echo "1/4 Database dump"
"${DB_EXEC[@]}" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc --no-owner' > "$OUT/database.dump"
# A dump that pg_restore cannot list is not a backup.
"${DB_EXEC[@]}" sh -c 'pg_restore --list > /dev/null' < "$OUT/database.dump"

if [[ $CAPTURES -eq 1 ]]; then
  echo "2/4 Capture files"
  if [[ -n "$CAP_DIR" ]]; then
    tar -C "$CAP_DIR" -cf "$OUT/captures.tar" .
  else
    docker run --rm -v "$CAP_VOLUME:/data:ro" postgres:16-alpine tar -C /data -cf - . > "$OUT/captures.tar"
  fi
else
  echo "2/4 Capture files skipped (--no-captures)"
fi

echo "3/4 Checksums and encryption"
{
  echo "created=$STAMP"
  echo "schema=$("${DB_EXEC[@]}" sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT max(version::int) FROM flyway_schema_history WHERE success"')"
  echo "encrypted=$([[ -n "$PASS" ]] && echo yes || echo no)"
} > "$OUT/MANIFEST"
for f in "$OUT"/database.dump "$OUT"/captures.tar; do
  [[ -f "$f" ]] || continue
  if [[ -n "$PASS" ]]; then
    openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass "file:$PASS" -in "$f" -out "$f.enc"
    rm -f "$f"
  fi
done
(cd "$OUT" && sha256sum database.dump* captures.tar* 2>/dev/null > SHA256SUMS)
trap - ERR

echo "4/4 Removing backups older than $KEEP days"
find "$DIR" -maxdepth 1 -type d -name 'netmon-*' -mtime +"$KEEP" -print -exec rm -rf {} +

echo "Backup written to $OUT:"
ls -l "$OUT"
