# FlowOps — System Architecture & Operations Reference

## Overview

FlowOps is a **PEGA-style Customer Case Management System** for telecom operations.
It manages customer complaint and request workflows from creation through resolution,
enforcing business rules at every state transition — analogous to how PEGA enforces
Flow Actions and When Rules.

---

## 1. System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         EXTERNAL CONSUMERS                                  │
│   Agent Web Portal     Mobile App       B2B Partner API       Reporting     │
└──────────┬─────────────────┬──────────────────┬──────────────────┬──────────┘
           │                 │                  │                  │
           ▼                 ▼                  ▼                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    NGINX Ingress Controller (TLS termination, rate-limit)    │
└─────────────────────────────────────┬───────────────────────────────────────┘
                                      │
┌─────────────────────────────────────▼───────────────────────────────────────┐
│                         Kubernetes Cluster (flowops namespace)               │
│                                                                             │
│   ┌─────────────────────────────────────────────────────────┐              │
│   │  FlowOps App (Spring Boot WAR on Apache Tomcat 10.1)     │  ×3 pods    │
│   │                                                          │  (HPA: 3–10) │
│   │  ┌────────────┐  ┌───────────────┐  ┌────────────────┐  │              │
│   │  │ REST Layer │→ │ Workflow Engine│→ │  JPA / Flyway  │  │              │
│   │  │(Controllers│  │ (Rule Chain)  │  │  (Hibernate)   │  │              │
│   │  │ + Actuator)│  │               │  │                │  │              │
│   │  └────────────┘  └───────────────┘  └───────┬────────┘  │              │
│   │                                             │           │              │
│   │  ┌──────────────────────────────────────────┘           │              │
│   │  │  Micrometer → Prometheus metrics                     │              │
│   │  │  OTel SDK  → Jaeger traces                           │              │
│   │  │  Logback   → Logstash (JSON)                         │              │
│   │  └─────────────────────────────────────────────────────  │              │
│   └──────────────────────────────────────────────────────────┘              │
│                              │                                              │
│   ┌───────────────────────────▼──────────────────────────────┐              │
│   │            PostgreSQL 16 (primary + replica)              │              │
│   │                     schema: flowops                       │              │
│   └──────────────────────────────────────────────────────────┘              │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┼───────────────────┐
              ▼               ▼                   ▼
┌─────────────────┐  ┌──────────────┐  ┌──────────────────┐
│   Prometheus    │  │  ELK Stack   │  │   Jaeger         │
│   + Grafana     │  │(ES+Logstash  │  │(Distributed      │
│  (Alertmanager) │  │  +Kibana)    │  │  Tracing UI)     │
└─────────────────┘  └──────────────┘  └──────────────────┘
```

---

## 2. Data Model

```sql
-- Three core tables in the flowops schema

customer_cases           -- The work item (PEGA "Case")
├─ id                   UUID PK
├─ case_number          VARCHAR(20) UNIQUE  -- CASE-2024-00001
├─ customer_id          VARCHAR
├─ status               ENUM: OPEN→IN_REVIEW→APPROVED/REJECTED→CLOSED
├─ priority             ENUM: LOW/MEDIUM/HIGH/CRITICAL (SLA hours: 72/24/8/2)
├─ case_type            ENUM: BILLING_DISPUTE, FRAUD_ALERT, PORT_REQUEST, ...
├─ assigned_agent_id    VARCHAR  (nullable)
├─ sla_due_at           TIMESTAMPTZ  (computed: created_at + SLA hours)
├─ version              BIGINT  (optimistic lock)
└─ created_at/updated_at

workflow_transitions     -- Immutable audit trail (PEGA "History")
├─ id                   UUID PK
├─ case_id              FK → customer_cases
├─ from_status / to_status
├─ performed_by         VARCHAR
├─ reason               TEXT
└─ rule_applied         VARCHAR  (which rule chain approved the transition)

case_notes               -- Agent notes (PEGA "Note" shape)
├─ id                   UUID PK
├─ case_id              FK → customer_cases
├─ author_id            VARCHAR
├─ content              TEXT
├─ is_internal          BOOLEAN  (false = visible to customer)
└─ created_at
```

**Key design decisions:**
- `version` column → **optimistic locking** prevents two agents transitioning the same case simultaneously
- `findByIdForUpdate()` → **pessimistic lock** used in the actual transition path for hard guarantees
- All timestamps in UTC (`TIMESTAMPTZ`); application timezone is UTC
- Separate `workflow_transitions` table keeps audit trail immutable (no UPDATEs ever)

---

## 3. Workflow State Machine

```
                    OPEN
                     │
                     │  agent picks up
                     ▼
                  IN_REVIEW ─────────── stuck >4h → SLA_ALERT
                   │      │
            approve│      │reject
                   ▼      ▼
               APPROVED  REJECTED
                   │      │
                   │      │ re-open or close
                   ▼      ▼
                   CLOSED ←──────────────
```

**Rule Engine (PEGA "When Rules" equivalent):**

| Rule Name                        | Priority | Logic                                              |
|----------------------------------|----------|----------------------------------------------------|
| `VALID_STATUS_TRANSITION`        | 1        | Enforces state machine — blocks illegal jumps      |
| `FRAUD_CASE_MUST_BE_ASSIGNED`    | 10       | FRAUD_ALERT cases need assigned agent before approve|
| `CRITICAL_CASE_RESOLUTION_REQUIRED` | 20    | CRITICAL cases need `resolution_notes` before close|

New rules are added by implementing `WorkflowRule` interface and annotating `@Component` — Spring auto-discovers them.

---

## 4. API Design

```
Base URL: /api/v1

Cases:
  POST   /cases                     Create new case
  GET    /cases/{id}                Get case by ID
  GET    /cases?status=IN_REVIEW    Search/filter (paginated)
  POST   /cases/{id}/transitions    Workflow transition (core operation)
  PATCH  /cases/{id}/assign         Assign to agent

Metrics:
  GET    /metrics/dashboard         Business dashboard (case counts, SLA status)

Operations:
  GET    /actuator/health           K8s liveness + readiness probes
  GET    /actuator/prometheus       Prometheus scrape endpoint
  GET    /actuator/metrics          All metric names
```

**Error responses use RFC 9457 Problem Details:**
```json
{
  "type": "/errors/workflow-violation",
  "status": 422,
  "detail": "Fraud cases must be assigned to an agent before approval",
  "timestamp": "2024-01-15T14:30:00Z"
}
```

---

## 5. Deployment Architecture

```
Environment      Cluster         Namespace        Replicas    DB
─────────────    ───────────────  ──────────────   ────────   ─────────────────
dev              local/minikube   (docker-compose) 1          flowops_dev
staging          k8s-staging      flowops-staging  2          flowops_staging (RDS)
prod             k8s-prod-eu-w1   flowops          3–10 (HPA) flowops_prod (RDS Multi-AZ)
```

**Tomcat configuration highlights:**
- NIO2 connector, maxThreads=200
- WAR deployment (Spring Boot `SpringBootServletInitializer`)
- Access logs in JSON format → ELK ingestion
- Non-root container user (UID 1000)

**Kubernetes highlights:**
- `topologySpreadConstraints` — pods spread across 3 AZs
- `maxUnavailable: 0` rolling update → zero-downtime deploys
- `startupProbe` gives up to 2 minutes for slow JVM startup
- HPA scales 3→10 pods on CPU >70% or memory >80%

---

## 6. Observability Stack

### Metrics (Prometheus + Grafana)

| Metric | Type | Alert Threshold |
|---|---|---|
| `workflow_transitions_total` | Counter | — (rate used for throughput) |
| `workflow_transition_duration_seconds` | Histogram | p95 > 2s → warning |
| `workflow_transitions_rejected_total` | Counter | rate >5% → warning |
| `cases_open_count` | Gauge | — |
| `cases_sla_breached_count` | Gauge | >0 → CRITICAL |
| `cases_stuck_count` | Gauge | >5 → CRITICAL |
| `http_server_requests_seconds` | Histogram | p95 >2s, error rate >1% |
| `hikaricp_connections_active` | Gauge | >90% of max → CRITICAL |
| `jvm_memory_used_bytes` (heap) | Gauge | >85% → warning |

### Logging (ELK)

Log flow: `Spring Boot Logback (JSON)` → `TCP → Logstash` → `Elasticsearch` → `Kibana`

Key Kibana saved searches:
- `tag: sla-breach` — all SLA breach events
- `tag: stuck-workflow` — workflow stuck events
- `log_level: ERROR` last 1h — error dashboard
- `tomcat.status: [500 TO 599]` — 5xx access log entries

MDC fields propagated in every log line:
- `traceId`, `spanId` — correlate with Jaeger
- `caseNumber`, `userId` — business context for rapid filtering

### Distributed Tracing (Jaeger + OpenTelemetry)

- Spring Boot auto-instruments all HTTP requests and DB queries
- `traceId` in every log line enables Kibana → Jaeger navigation
- Sampling: 100% in dev, 50% in staging, 5% in prod (configurable)

---

## 7. CI/CD Pipeline

```
Push to main/release/**
         │
    ┌────▼────┐
    │  Build  │  mvn package + unit tests + JaCoCo
    └────┬────┘
    ┌────▼──────────┐
    │ Security Scan │  OWASP Dependency Check + Trivy image scan
    └────┬──────────┘
    ┌────▼────────────┐
    │ Docker Build    │  multi-stage: builder → Tomcat runtime
    │ + Push to GHCR  │
    └────┬────────────┘
    ┌────▼──────────┐
    │Deploy Staging │  kubectl set image → rollout status → smoke tests
    └────┬──────────┘
    ┌────▼──────────┐
    │ Manual Approve│  GitHub Environments gate (required reviewer)
    └────┬──────────┘
    ┌────▼──────────┐
    │ Deploy Prod   │  rolling update (maxUnavailable=0) → Slack notify
    └───────────────┘
```

---

## 8. Business Continuity (BCM)

### Backup Strategy

| Type    | Schedule         | Retention | Storage                |
|---------|------------------|-----------|------------------------|
| Daily   | 02:00 UTC daily  | 30 days   | S3 `daily/` (IA class) |
| Weekly  | Sunday 02:00 UTC | 12 weeks  | S3 `weekly/`           |
| Monthly | 1st of month     | 3 months  | S3 `monthly/`          |

Backup script pushes a success metric to Pushgateway; Prometheus alerts if `flowops_last_backup_timestamp_seconds` is older than 25 hours.

### Recovery Plan

| Scenario              | RTO   | RPO   | Action                                                  |
|-----------------------|-------|-------|---------------------------------------------------------|
| Single pod crash      | <30s  | 0     | K8s restarts pod; other pods handle traffic             |
| All pods down         | <5m   | 0     | `kubectl rollout undo` to previous image                |
| DB primary failure    | <5m   | <1m   | Patroni auto-failover to replica                        |
| Full DB loss          | <2h   | <24h  | Restore from S3 via `restore.sh`, replay WAL if available|
| Full cluster failure  | <4h   | <24h  | Spin up new cluster, deploy from CI artifacts, restore DB|

### High Availability Design

- **3 app replicas** across 3 AZs — loss of 1 AZ does not cause downtime
- **PostgreSQL streaming replication** — replica is always max 1 WAL file behind
- **HPA** — handles 3× traffic spike without manual intervention
- **Rolling updates** with `maxUnavailable=0` — deploys never cause downtime

---

## 9. Incident Scenarios

### Scenario 1: Workflow Stuck

**What happens:** `cases_stuck_count > 5` alert fires. Agent workload spiked; unassigned IN_REVIEW cases pile up.

**Detection:** Prometheus → Alertmanager → PagerDuty + Slack `#flowops-incidents`.

**Resolution:** See [runbooks/workflow-stuck.md](../runbooks/workflow-stuck.md)

**Simulated by:**
```sql
-- Simulate 10 stuck cases for drill
UPDATE flowops.customer_cases
SET updated_at = NOW() - INTERVAL '5 hours'
WHERE status = 'IN_REVIEW' LIMIT 10;
```

---

### Scenario 2: DB Failure

**What happens:** `DatabaseDown` fires. Postgres pod OOMKilled on node with insufficient memory.

**Detection:** `up{job="postgres"} == 0` evaluated every 15s; alert fires after 1 minute.

**Resolution:** See [runbooks/db-failure.md](../runbooks/db-failure.md)

**Simulated by:**
```bash
kubectl exec postgres-pod -n flowops -- kill -9 1  # kill postgres process
```

---

### Scenario 3: Service Crash

**What happens:** Memory leak in case search query causes OOM after 6 hours of high load. All 3 pods OOMKilled within 2 minutes.

**Detection:** `HighErrorRate` fires (5xx > 1%); K8s `CrashLoopBackOff` events visible.

**Resolution:** See [runbooks/service-crash.md](../runbooks/service-crash.md)

**Simulated by:**
```bash
# Lower memory limit to force OOM faster
kubectl patch deployment flowops -n flowops \
  --type='json' \
  -p='[{"op":"replace","path":"/spec/template/spec/containers/0/resources/limits/memory","value":"256Mi"}]'
```

---

## 10. CV Bullet Points

The following bullet points are ready to use on a CV or LinkedIn profile:

---

### Technical Skills Demonstrated

- **Designed and implemented** a PEGA-style Customer Case Management System using Java 17, Spring Boot 3.2, and PostgreSQL 16, supporting 7 case types and a 5-state workflow engine with pluggable business rules.

- **Engineered a rule-based workflow engine** (analogous to PEGA Flow Actions) using Chain of Responsibility pattern; business rules added as `@Component` beans with zero code change to the core engine.

- **Built zero-downtime Kubernetes deployment** using rolling updates (`maxUnavailable: 0`), topology spread constraints across 3 AZs, HPA (3–10 pods), and liveness/readiness/startup probes backed by Spring Boot Actuator.

- **Implemented full observability stack**: Prometheus + Grafana for metrics (request rate, p95/p99 latency, workflow throughput, SLA breach tracking), ELK (Elasticsearch/Logstash/Kibana) for structured JSON logging with MDC trace correlation, and Jaeger via OpenTelemetry for distributed tracing.

- **Designed and operationalized alerting rules** covering HTTP error rate, workflow stagnation, JVM heap pressure, HikariCP pool exhaustion, and database unavailability — all with defined severity, owner, and runbook links.

- **Created production-grade CI/CD pipeline** (GitHub Actions): unit tests, JaCoCo coverage, SonarQube analysis, OWASP Dependency Check, Trivy container vulnerability scan, multi-stage Docker build (builder + Tomcat runtime), automated staging deploy + smoke tests, and manual-gate production deploy with Slack notification.

- **Designed BCM strategy**: daily/weekly/monthly PostgreSQL pg_dump backups to S3 with 30/12/3 retention, integrity verification, automated alerting on backup failure, and documented RTO/RPO targets per failure scenario.

- **Wrote operational runbooks** for three incident scenarios (stuck workflow, DB failure, service crash) — each with step-by-step diagnosis, remediation commands, and post-incident action items.

- **Applied database best practices**: Flyway schema migrations, optimistic locking (`@Version`), pessimistic write lock for concurrent state transitions, composite indexes for query patterns, and SLA-filtered partial indexes.

- **Deployed on Apache Tomcat 10.1** using WAR packaging (`SpringBootServletInitializer`), NIO2 connector tuning (200 threads, connection timeout, Gzip compression), and JSON access logs for ELK ingestion.
