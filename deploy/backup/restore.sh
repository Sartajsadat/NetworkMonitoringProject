#!/usr/bin/env bash
# NetMon restore. Replaces the CURRENT database (and capture files) with a backup made by backup.sh.
#
#   sudo ./restore.sh --from /var/backups/netmon/netmon-20261102T030000Z [--passphrase-file FILE] [--yes]
#                     [--project netmon] [--db-container NAME] [--captures-volume NAME | --captures-dir PATH]
#                     [--no-captures]
#
# Steps: verify checksums -> decrypt -> stop backend/frontend (Compose) -> restore database in one
# transaction -> restore capture files -> start services. Agents keep their credentials (stored in the
# database). Restoring to a database with a NEWER schema than the backup is refused: restore into the
# matching release, then upgrade (Flyway applies newer migrations at start-up).
set -euo pipefail

FROM="" PASS="" YES=0 PROJECT=netmon DB_CONTAINER="" CAP_VOLUME="" CAP_DIR="" CAPTURES=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --from) FROM="$2"; shift 2 ;;
    --passphrase-file) PASS="$2"; shift 2 ;;
    --yes) YES=1; shift ;;
    --project) PROJECT="$2"; shift 2 ;;
    --db-container) DB_CONTAINER="$2"; shift 2 ;;
    --captures-volume) CAP_VOLUME="$2"; shift 2 ;;
    --captures-dir) CAP_DIR="$2"; shift 2 ;;
    --no-captures) CAPTURES=0; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done
[[ -d "$FROM" && -f "$FROM/MANIFEST" && -f "$FROM/SHA256SUMS" ]] || { echo "--from must be a backup directory made by backup.sh" >&2; exit 2; }
COMPOSE=1
if [[ -n "$DB_CONTAINER" ]]; then DB_EXEC=(docker exec -i "$DB_CONTAINER"); COMPOSE=0
else DB_EXEC=(docker compose -p "$PROJECT" exec -T db); fi
[[ -n "$CAP_VOLUME$CAP_DIR" ]] || CAP_VOLUME="${PROJECT}_capture-data"

echo "1/5 Verifying checksums"
(cd "$FROM" && sha256sum --check --quiet SHA256SUMS) || { echo "Checksum mismatch: the backup is damaged or was altered. Not restoring." >&2; exit 1; }
cat "$FROM/MANIFEST"
BACKUP_SCHEMA=$(sed -n 's/^schema=//p' "$FROM/MANIFEST")
CURRENT_SCHEMA=$("${DB_EXEC[@]}" sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT max(version::int) FROM flyway_schema_history WHERE success" 2>/dev/null' || true)
if [[ -n "$CURRENT_SCHEMA" && -n "$BACKUP_SCHEMA" && "$CURRENT_SCHEMA" -gt "$BACKUP_SCHEMA" ]]; then
  echo "The database has schema $CURRENT_SCHEMA but the backup has $BACKUP_SCHEMA. Deploy the matching release first." >&2; exit 1
fi

if [[ $YES -ne 1 ]]; then
  read -r -p "This REPLACES the current NetMon data with the backup from $(sed -n 's/^created=//p' "$FROM/MANIFEST"). Type RESTORE: " answer
  [[ "$answer" == "RESTORE" ]] || { echo "Cancelled."; exit 1; }
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
echo "2/5 Decrypting"
for f in database.dump captures.tar; do
  if [[ -f "$FROM/$f.enc" ]]; then
    [[ -r "$PASS" ]] || { echo "The backup is encrypted: give --passphrase-file" >&2; exit 2; }
    openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass "file:$PASS" -in "$FROM/$f.enc" -out "$WORK/$f"
  elif [[ -f "$FROM/$f" ]]; then
    cp "$FROM/$f" "$WORK/$f"
  fi
done

echo "3/5 Stopping the platform"
if [[ $COMPOSE -eq 1 ]]; then docker compose -p "$PROJECT" stop backend frontend
else echo "   (not a Compose deployment: make sure the backend is stopped)"; fi

echo "4/5 Restoring the database (single transaction)"
"${DB_EXEC[@]}" sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --single-transaction' < "$WORK/database.dump"

if [[ $CAPTURES -eq 1 && -f "$WORK/captures.tar" ]]; then
  echo "5/5 Restoring capture files"
  if [[ -n "$CAP_DIR" ]]; then
    find "$CAP_DIR" -mindepth 1 -delete && tar -C "$CAP_DIR" -xf "$WORK/captures.tar"
  else
    docker run --rm -i -v "$CAP_VOLUME:/data" postgres:16-alpine sh -c 'find /data -mindepth 1 -delete && tar -C /data -xf -' < "$WORK/captures.tar"
  fi
else
  echo "5/5 Capture files not restored"
fi

if [[ $COMPOSE -eq 1 ]]; then docker compose -p "$PROJECT" start backend frontend; fi
echo "Restore finished. Check the platform health page and sign in."
