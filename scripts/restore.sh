#!/usr/bin/env bash
# FlowOps Database Restore Script
# Usage: ./restore.sh <backup-filename-on-s3> [--dry-run]
# Example: ./restore.sh flowops_flowops_prod_20240115_020000.sql.gz
set -euo pipefail

BACKUP_FILE="${1:?Usage: restore.sh <backup-file>}"
DRY_RUN="${2:-}"
DB_HOST="${DB_HOST:-postgres-service}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-flowops_prod}"
DB_USER="${DB_USER:-flowops}"
S3_BUCKET="${S3_BUCKET:-s3://flowops-backups}"
RESTORE_DIR="/tmp/restore"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

log "============================================================"
log " FlowOps Database Restore"
log " Target DB:   $DB_NAME @ $DB_HOST:$DB_PORT"
log " Backup file: $BACKUP_FILE"
log " Dry run:     ${DRY_RUN:-NO}"
log "============================================================"

if [ -z "$DRY_RUN" ]; then
    read -rp "WARNING: This will OVERWRITE the current database. Type 'yes' to continue: " CONFIRM
    [ "$CONFIRM" = "yes" ] || { log "Aborted."; exit 0; }
fi

mkdir -p "$RESTORE_DIR"

# ── Download from S3 ──────────────────────────────────────────────────────────
log "Downloading $BACKUP_FILE from S3..."
# Try daily first, then weekly/monthly
for PREFIX in daily weekly monthly; do
    if aws s3 ls "$S3_BUCKET/$PREFIX/$BACKUP_FILE" >/dev/null 2>&1; then
        aws s3 cp "$S3_BUCKET/$PREFIX/$BACKUP_FILE" "$RESTORE_DIR/$BACKUP_FILE"
        log "Downloaded from $PREFIX archive."
        break
    fi
done

[ -f "$RESTORE_DIR/$BACKUP_FILE" ] || { log "ERROR: Backup file not found in S3"; exit 1; }

# ── Verify integrity before restore ──────────────────────────────────────────
log "Verifying backup integrity..."
gzip -t "$RESTORE_DIR/$BACKUP_FILE" && log "Integrity: OK"

if [ -n "$DRY_RUN" ]; then
    log "DRY RUN complete. File downloaded and verified. No changes made."
    rm -f "$RESTORE_DIR/$BACKUP_FILE"
    exit 0
fi

# ── Scale down application (avoid writes during restore) ─────────────────────
log "Scaling down FlowOps application..."
kubectl scale deployment flowops --replicas=0 -n flowops
sleep 5

# ── Drop and recreate schema ──────────────────────────────────────────────────
log "Dropping and recreating flowops schema..."
PGPASSWORD="$DB_PASSWORD" psql \
    -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" <<-SQL
        DROP SCHEMA IF EXISTS flowops CASCADE;
        CREATE SCHEMA flowops;
SQL

# ── Restore ───────────────────────────────────────────────────────────────────
log "Restoring from backup (this may take several minutes)..."
zcat "$RESTORE_DIR/$BACKUP_FILE" \
    | PGPASSWORD="$DB_PASSWORD" pg_restore \
        --host="$DB_HOST" \
        --port="$DB_PORT" \
        --username="$DB_USER" \
        --dbname="$DB_NAME" \
        --schema=flowops \
        --no-acl \
        --no-owner \
        --exit-on-error \
        -

log "Restore completed. Validating row counts..."
PGPASSWORD="$DB_PASSWORD" psql \
    -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
    -c "SELECT relname, n_live_tup FROM pg_stat_user_tables WHERE schemaname='flowops' ORDER BY relname;"

# ── Scale application back up ─────────────────────────────────────────────────
log "Scaling FlowOps back to 3 replicas..."
kubectl scale deployment flowops --replicas=3 -n flowops
kubectl rollout status deployment/flowops -n flowops --timeout=5m

# ── Run smoke tests ───────────────────────────────────────────────────────────
log "Running smoke tests..."
sleep 10
curl -sf http://flowops-service/actuator/health | grep -q '"status":"UP"' \
    && log "Smoke test: PASSED" \
    || { log "Smoke test FAILED — investigate before sending traffic"; exit 1; }

rm -f "$RESTORE_DIR/$BACKUP_FILE"
log "Restore complete. Verify data integrity with the business team before resuming operations."
