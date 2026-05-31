# Runbook: Database Failure

**Alert:** `DatabaseDown` / `HikariConnectionPoolExhausted`
**Severity:** CRITICAL
**Owner:** Platform-Ops / DBA Team
**Last Updated:** 2024-01

---

## Symptom

`DatabaseDown`: Prometheus `up{job="postgres"}` == 0 — PostgreSQL is unreachable.
`HikariConnectionPoolExhausted`: Pool >90% utilized — requests start timing out.
Application returns 500 errors; Kibana shows `HikariPool-1 - Connection is not available`.

---

## Immediate Investigation (< 3 minutes)

### 1. Verify PostgreSQL status

```bash
# From the postgres pod or node:
kubectl exec -it postgres-pod -n flowops -- pg_isready -U flowops
# Or via psql:
psql -h $DB_HOST -U flowops -c "SELECT NOW();" 2>&1
```

### 2. Check PostgreSQL pod health

```bash
kubectl get pods -n flowops -l app=postgres
kubectl describe pod postgres-<pod-id> -n flowops
kubectl logs postgres-<pod-id> -n flowops --tail=100
```

### 3. Check HikariCP metrics

```bash
# Prometheus query:
hikaricp_connections_active / hikaricp_connections_max
hikaricp_connections_timeout_total   # connections that failed to acquire

# Or Spring Boot actuator:
curl http://flowops-service:8080/actuator/metrics/hikaricp.connections.active
```

### 4. Check for slow / blocking queries

```bash
psql -h $DB_HOST -U flowops -d flowops_prod -c "
  SELECT pid, now() - pg_stat_activity.query_start AS duration, query, state
  FROM pg_stat_activity
  WHERE state = 'active'
    AND now() - pg_stat_activity.query_start > INTERVAL '5 seconds'
  ORDER BY duration DESC;
"
```

---

## Root Cause Categories

| Root Cause                          | Indicator                                        |
|-------------------------------------|--------------------------------------------------|
| DB pod OOMKilled / crashed          | `kubectl describe pod` shows OOMKilled           |
| PVC full (no disk space)            | Postgres logs: `no space left on device`         |
| Max connections exhausted           | `FATAL: too many connections` in pg logs         |
| Long-running query holding lock     | `pg_stat_activity` shows old `active` query      |
| Network partition                   | App can't reach DB but DB is otherwise healthy   |
| Read replica lag causing failover   | Patroni/HA logs show leader election             |

---

## Resolution Steps

### Scenario A — Pod crashed / OOMKilled

```bash
# Restart postgres pod (triggers WAL recovery if crash)
kubectl rollout restart deployment/postgres -n flowops
# Monitor recovery:
kubectl logs -f postgres-<new-pod-id> -n flowops | grep -E "ready to accept|FATAL|ERROR"
# After recovery, rolling-restart the app to clear stale connections:
kubectl rollout restart deployment/flowops -n flowops
```

### Scenario B — Disk full

```bash
# Immediate: terminate idle connections to free WAL
psql -h $DB_HOST -U postgres -c "
  SELECT pg_terminate_backend(pid)
  FROM pg_stat_activity
  WHERE state = 'idle' AND datname = 'flowops_prod';
"
# Expand PVC (requires storage class that supports expansion):
kubectl patch pvc postgres-data -n flowops \
  -p '{"spec":{"resources":{"requests":{"storage":"50Gi"}}}}'
# Long-term: enable pg_partman for table partitioning + retention policy
```

### Scenario C — Max connections exhausted

```bash
# Check current connection count vs limit
psql -h $DB_HOST -U postgres -c "
  SELECT count(*), datname FROM pg_stat_activity GROUP BY datname;
"
psql -h $DB_HOST -U postgres -c "SHOW max_connections;"
# Terminate idle connections
psql -h $DB_HOST -U postgres -c "
  SELECT pg_terminate_backend(pid)
  FROM pg_stat_activity
  WHERE state = 'idle' AND datname = 'flowops_prod'
    AND now() - state_change > INTERVAL '5 minutes';
"
# If using PgBouncer — restart pooler:
kubectl rollout restart deployment/pgbouncer -n flowops
```

### Scenario D — Blocking query (lock contention)

```bash
# Find blocker
psql -h $DB_HOST -U postgres -c "
  SELECT blocking_pids, pid, query, wait_event
  FROM pg_stat_activity
  JOIN (
    SELECT ARRAY(SELECT pid FROM pg_locks WHERE NOT granted) blocking_pids
  ) bl ON TRUE
  WHERE pid = ANY(blocking_pids);
"
# Terminate the blocking PID
psql -h $DB_HOST -U postgres -c "SELECT pg_terminate_backend(<blocking_pid>);"
```

---

## Failover to Read Replica (if primary down > 5 minutes)

```bash
# If using Patroni HA:
patronictl -c /etc/patroni/config.yml failover flowops-cluster
# Update application configmap to point to new primary:
kubectl edit configmap flowops-config -n flowops
# Change DB_HOST to new primary endpoint, then rolling restart:
kubectl rollout restart deployment/flowops -n flowops
```

---

## Post-Incident

1. Confirm all stuck transitions completed after DB recovery.
2. Check for data integrity: any cases left in inconsistent state due to partial transactions.
3. Review HikariCP pool size — if exhaustion happened, consider increasing `maximum-pool-size`.
4. File RCA within 24h: timeline, blast radius, MTTR, preventive action.

---

## Prevention

- Set up PostgreSQL streaming replication + Patroni for automatic failover.
- Monitor `pg_stat_replication` lag on replica.
- PgBouncer as connection pooler in front of PostgreSQL reduces per-app connection pressure.
- Periodic VACUUM ANALYZE to prevent table bloat causing slow queries.
