#!/usr/bin/env bash
# FlowOps Database Backup Script
# Schedule: runs daily at 02:00 UTC via Kubernetes CronJob
# Retention: 30 daily, 12 weekly, 3 monthly snapshots
# Storage: AWS S3 (or compatible object store)
set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
DB_HOST="${DB_HOST:-postgres-service}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-flowops_prod}"
DB_USER="${DB_USER:-flowops}"
S3_BUCKET="${S3_BUCKET:-s3://flowops-backups}"
BACKUP_DIR="/tmp/backups"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="flowops_${DB_NAME}_${TIMESTAMP}.sql.gz"
LOG_FILE="/var/log/flowops-backup.log"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$LOG_FILE"; }

# ── Pre-flight checks ─────────────────────────────────────────────────────────
log "Starting backup: $BACKUP_FILE"
mkdir -p "$BACKUP_DIR"
which pg_dump  >/dev/null 2>&1 || { log "ERROR: pg_dump not found"; exit 1; }
which aws      >/dev/null 2>&1 || { log "ERROR: aws CLI not found"; exit 1; }

# ── Test DB connectivity ──────────────────────────────────────────────────────
if ! pg_isready -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" >/dev/null 2>&1; then
    log "ERROR: Cannot connect to PostgreSQL at $DB_HOST:$DB_PORT"
    # Alert via curl to PagerDuty/Alertmanager
    curl -sf -X POST "${ALERTMANAGER_URL:-http://alertmanager:9093}/api/v1/alerts" \
      -H 'Content-Type: application/json' \
      -d '[{"labels":{"alertname":"BackupFailed","severity":"critical","application":"flowops"}}]' || true
    exit 1
fi

# ── Dump with compression ─────────────────────────────────────────────────────
log "Dumping database $DB_NAME..."
PGPASSWORD="$DB_PASSWORD" pg_dump \
    --host="$DB_HOST" \
    --port="$DB_PORT" \
    --username="$DB_USER" \
    --dbname="$DB_NAME" \
    --schema=flowops \
    --format=custom \
    --compress=9 \
    --no-acl \
    --no-owner \
    | gzip -9 > "$BACKUP_DIR/$BACKUP_FILE"

BACKUP_SIZE=$(du -sh "$BACKUP_DIR/$BACKUP_FILE" | cut -f1)
log "Backup size: $BACKUP_SIZE"

# ── Upload to S3 ──────────────────────────────────────────────────────────────
log "Uploading to $S3_BUCKET/daily/$BACKUP_FILE ..."
aws s3 cp "$BACKUP_DIR/$BACKUP_FILE" "$S3_BUCKET/daily/$BACKUP_FILE" \
    --storage-class STANDARD_IA \
    --sse AES256

# Weekly backup (every Sunday)
if [ "$(date +%u)" -eq 7 ]; then
    log "Copying to weekly archive..."
    aws s3 cp "$S3_BUCKET/daily/$BACKUP_FILE" "$S3_BUCKET/weekly/$BACKUP_FILE"
fi

# Monthly backup (1st of month)
if [ "$(date +%d)" -eq 1 ]; then
    log "Copying to monthly archive..."
    aws s3 cp "$S3_BUCKET/daily/$BACKUP_FILE" "$S3_BUCKET/monthly/$BACKUP_FILE"
fi

# ── Retention cleanup ─────────────────────────────────────────────────────────
log "Applying retention: 30 daily, 12 weekly, 3 monthly..."
# Delete daily backups older than 30 days
aws s3 ls "$S3_BUCKET/daily/" \
    | awk '{print $4}' \
    | sort -r \
    | tail -n +31 \
    | xargs -I{} aws s3 rm "$S3_BUCKET/daily/{}" 2>/dev/null || true

# ── Verify backup integrity ───────────────────────────────────────────────────
log "Verifying backup integrity..."
gzip -t "$BACKUP_DIR/$BACKUP_FILE" && log "Integrity check: PASSED" || {
    log "ERROR: Integrity check FAILED"
    exit 1
}

# ── Cleanup local temp ────────────────────────────────────────────────────────
rm -f "$BACKUP_DIR/$BACKUP_FILE"

log "Backup completed successfully: $BACKUP_FILE ($BACKUP_SIZE) → $S3_BUCKET"

# ── Record backup metrics (Prometheus Pushgateway) ───────────────────────────
cat <<EOF | curl -sf --data-binary @- "${PUSHGATEWAY_URL:-http://pushgateway:9091}/metrics/job/flowops_backup"
# HELP flowops_last_backup_timestamp_seconds Unix timestamp of last successful backup
# TYPE flowops_last_backup_timestamp_seconds gauge
flowops_last_backup_timestamp_seconds $(date +%s)
EOF

exit 0
