# Runbook: Application Service Crash / Pod CrashLoop

**Alert:** `HighErrorRate` / Kubernetes pod in `CrashLoopBackOff`
**Severity:** CRITICAL
**Owner:** Platform-Ops Team
**Last Updated:** 2024-01

---

## Symptom

One or more FlowOps pods crash and Kubernetes attempts to restart them.
API returns 503 from the load balancer. `kubectl get pods` shows `CrashLoopBackOff` or `Error`.

---

## Immediate Investigation (< 5 minutes)

### 1. Check pod status

```bash
kubectl get pods -n flowops -l app=flowops
kubectl describe pod flowops-<pod-id> -n flowops
# Look for: OOMKilled, Exit Code, Last State, Restart Count
```

### 2. Check application logs from the crashed pod

```bash
# Current logs:
kubectl logs flowops-<pod-id> -n flowops --tail=200
# Previous crashed container's logs (most useful after crash):
kubectl logs flowops-<pod-id> -n flowops --previous --tail=200
```

### 3. Check Kibana for the error

```kibana
index: flowops-logs-*
filter: log_level:ERROR OR log_level:FATAL
time: last 30 minutes
sort: @timestamp desc
```

### 4. Check Kubernetes events

```bash
kubectl get events -n flowops --sort-by='.lastTimestamp' | tail -20
```

---

## Root Cause Categories

| Root Cause                       | Exit Code | Indicator                                              |
|----------------------------------|-----------|--------------------------------------------------------|
| OutOfMemoryError                 | 137       | OOMKilled in pod describe; heap dump in /tmp           |
| Unhandled exception on startup   | 1         | Stack trace in startup logs, missing config/secret     |
| DB connection refused at startup | 1         | `Connection refused` or `HikariPool` timeout in logs   |
| Config/Secret not found          | 1         | `Could not resolve placeholder` in Spring logs         |
| Port already in use              | 1         | `Address already in use: 8080`                         |
| CPU throttling / OOM at OS level | 137/143   | Node-level `dmesg` shows OOM killer                    |

---

## Resolution Steps

### Scenario A — OOMKilled (heap overflow)

```bash
# Check heap dump if written to /tmp (before pod restarts and /tmp is cleared)
kubectl exec flowops-<pod-id> -n flowops -- ls -lh /tmp/*.hprof 2>/dev/null

# Copy heap dump before pod restarts:
kubectl cp flowops-<pod-id>:/tmp/heapdump.hprof ./heapdump.hprof -n flowops

# Immediate fix: increase memory limit in deployment
kubectl patch deployment flowops -n flowops \
  --type='json' \
  -p='[{"op":"replace","path":"/spec/template/spec/containers/0/resources/limits/memory","value":"2Gi"}]'

# Monitor after patch:
kubectl rollout status deployment/flowops -n flowops
```

Analyze heap dump with Eclipse MAT or VisualVM to find leak source.

### Scenario B — Startup failure (missing config/secret)

```bash
# Check for missing env vars:
kubectl exec flowops-<new-pod-id> -n flowops -- env | grep -E "DB_|JWT_"
# If secret is missing, re-apply:
kubectl apply -f k8s/secret.yaml -n flowops
# Trigger a rollout to pick up new secret:
kubectl rollout restart deployment/flowops -n flowops
```

### Scenario C — DB connection refused at startup

```bash
# Verify DB is healthy first (see db-failure.md)
# Check readiness probe — pod shouldn't receive traffic until DB is reachable
# Temporarily disable readiness probe to allow pod to try connecting:
# (use with caution — only in emergency)
kubectl patch deployment flowops -n flowops \
  --type='json' \
  -p='[{"op":"remove","path":"/spec/template/spec/containers/0/readinessProbe"}]'
# Re-add readinessProbe once DB is stable
```

### Scenario D — All pods crashing (full outage)

```bash
# Roll back to last known good image:
kubectl rollout undo deployment/flowops -n flowops
# Verify rollback:
kubectl rollout status deployment/flowops -n flowops
# Check which image was rolled back to:
kubectl describe deployment flowops -n flowops | grep Image
```

---

## While Service is Recovering

1. **Enable maintenance page** via ingress annotation:
   ```bash
   kubectl annotate ingress flowops-ingress -n flowops \
     nginx.ingress.kubernetes.io/custom-http-errors="503" \
     nginx.ingress.kubernetes.io/default-backend="maintenance-service"
   ```
2. Notify stakeholders via Slack `#incident-channel`.
3. Confirm HPA is not causing cascading restarts (scale down to 1 if needed).

---

## Post-Incident

1. Preserve crashed pod logs before pod is GC'd:
   ```bash
   kubectl logs flowops-<crashed-pod> -n flowops --previous > incident-$(date +%Y%m%d).log
   ```
2. File full RCA: timeline, root cause, data impact, MTTR.
3. For OOM: add heap profiling to staging CI; lower `Xmx` to reproduce.
4. For config issue: add startup validation step in CI pipeline.
5. Review liveness probe `failureThreshold` — too aggressive probes can kill slow-starting pods.
