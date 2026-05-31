# Runbook: Stuck Workflow Cases

**Alert:** `WorkflowStuckCasesDetected`
**Severity:** CRITICAL
**Owner:** Platform-Ops Team
**Last Updated:** 2024-01

---

## Symptom

Cases remain in `IN_REVIEW` status for more than 4 hours without any agent action.
The scheduled watchdog in `CaseService.detectStuckWorkflows()` logs `STUCK_WORKFLOW`
events and emits the `cases.stuck.count` gauge to Prometheus.

---

## Immediate Investigation (< 5 minutes)

### 1. Check how many cases are stuck

```bash
# Grafana: cases.stuck.count gauge
# Or directly in Postgres:
psql -h $DB_HOST -U flowops -d flowops_prod -c "
  SELECT case_number, customer_name, priority, assigned_agent_id, updated_at
  FROM flowops.customer_cases
  WHERE status = 'IN_REVIEW'
    AND updated_at < NOW() - INTERVAL '4 hours'
  ORDER BY priority DESC, updated_at ASC;
"
```

### 2. Check for recent application errors around stuck cases

```bash
# Kibana: filter by tag=stuck-workflow OR search for STUCK_WORKFLOW in message
# Or via API:
curl -s "http://elasticsearch:9200/flowops-logs-*/search" \
  -H 'Content-Type: application/json' \
  -d '{"query":{"match":{"message":"STUCK_WORKFLOW"}},"size":20}'
```

### 3. Check if agents are online

```bash
# If agent assignment table exists, query it; otherwise verify via HR portal
# Check if any unassigned stuck cases exist:
psql -h $DB_HOST -U flowops -d flowops_prod -c "
  SELECT COUNT(*) FROM flowops.customer_cases
  WHERE status = 'IN_REVIEW' AND assigned_agent_id IS NULL;
"
```

---

## Root Cause Categories

| Root Cause                        | Indicator                                           |
|-----------------------------------|-----------------------------------------------------|
| Agents offline / no coverage      | Bulk cases stuck, no recent transitions             |
| Rule engine blocking transitions  | `workflow.transitions.rejected_total` metric rising |
| Unassigned cases (no agent)       | `assigned_agent_id IS NULL` count high              |
| Application bug                   | Exception stack traces in Kibana for those cases    |
| Database transaction lock         | `pg_locks` showing blocked transactions             |

---

## Resolution Steps

### Scenario A — Agents offline (e.g., weekend/holiday)

```bash
# Re-assign all unattended stuck cases to on-call agent
psql -h $DB_HOST -U flowops -d flowops_prod -c "
  UPDATE flowops.customer_cases
  SET assigned_agent_id = 'ONCALL-AGENT-001',
      updated_at = NOW()
  WHERE status = 'IN_REVIEW'
    AND updated_at < NOW() - INTERVAL '4 hours';
"
# Then notify on-call agent via PagerDuty / Slack
```

### Scenario B — Rule engine blocking transitions

```bash
# Check rule violations in logs
grep "WorkflowRuleViolationException" /var/log/flowops/application.log | tail -20
# Fix the data condition (e.g., assign agent for FRAUD_ALERT cases)
# Then manually re-trigger review via API:
curl -X POST https://flowops.telecom-internal.com/api/v1/cases/{CASE_ID}/transitions \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"targetStatus":"APPROVED","reason":"Admin override — stuck workflow resolution"}'
```

### Scenario C — DB transaction lock

```bash
# Identify blocking transactions
psql -h $DB_HOST -U flowops -d flowops_prod -c "
  SELECT pid, query, state, wait_event_type, wait_event, now() - query_start AS duration
  FROM pg_stat_activity
  WHERE state = 'active' AND wait_event_type = 'Lock'
  ORDER BY duration DESC;
"
# If a transaction is stuck > 10 min, terminate it:
psql -h $DB_HOST -U flowops -d flowops_prod -c "SELECT pg_terminate_backend(<pid>);"
```

---

## Post-Incident

1. Document in incident tracker: how many cases affected, duration, root cause.
2. If agent coverage gap: update on-call rotation schedule.
3. If rule engine bug: create GitHub issue, hotfix to staging, deploy to prod.
4. Review stuck-case threshold (currently 4h) — adjust if false positives occur.

---

## Prevention

- Ensure minimum 1 agent online per shift via staffing tool.
- Add `WorkflowStuckCasesDetected` to PagerDuty escalation (currently Slack-only).
- Consider auto-escalation: after 6h stuck, auto-assign to supervisor.
