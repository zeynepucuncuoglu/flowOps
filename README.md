# FlowOps — PEGA-Style Customer Case Management

Enterprise-grade workflow and case management system simulating real telecom operations.
Built with Java 21 + Spring Boot 3.3 + PostgreSQL, deployed on Apache Tomcat 10.1 + Kubernetes.

---

## Quick Start (local dev)

```bash
# 1. Start full stack (app + postgres + prometheus + grafana + elk + jaeger)
docker-compose -f docker/docker-compose.yml up -d

# 2. Wait ~60s for Tomcat to start, then test:
curl http://localhost:8080/actuator/health

# 3. Create a case
curl -X POST http://localhost:8080/api/v1/cases \
  -H "Content-Type: application/json" \
  -u admin:admin \
  -d '{
    "customerId": "CUST-001",
    "customerName": "Test Subscriber",
    "caseType": "BILLING_DISPUTE",
    "priority": "HIGH",
    "subject": "Double charge on invoice"
  }'

# 4. Transition case to IN_REVIEW
curl -X POST http://localhost:8080/api/v1/cases/{id}/transitions \
  -H "Content-Type: application/json" \
  -u admin:admin \
  -d '{"targetStatus": "IN_REVIEW", "reason": "Triage complete"}'
```

---

## Workflow State Machine

```
OPEN → IN_REVIEW → APPROVED → CLOSED
              └──→ REJECTED → CLOSED
                        └──→ OPEN (re-open)
```

---

## Observability URLs (local)

| Service    | URL                          | Credentials |
|------------|------------------------------|-------------|
| Grafana    | http://localhost:3000         | admin/admin123 |
| Prometheus | http://localhost:9090         | — |
| Kibana     | http://localhost:5601         | — |
| Jaeger     | http://localhost:16686        | — |

---

## Project Structure

```
flowOps/
├── src/main/java/com/telecom/casemanagement/
│   ├── model/          Domain entities + enums
│   ├── dto/            Request/response DTOs
│   ├── repository/     JPA repositories
│   ├── engine/         Workflow rule engine + rules
│   ├── service/        Business logic + SLA watchdog
│   ├── controller/     REST controllers
│   ├── config/         Security + metrics config
│   └── exception/      Global error handler
├── src/main/resources/
│   ├── application*.yml            Multi-environment configs
│   ├── logback-prod.xml            JSON logging for ELK
│   └── db/migration/               Flyway SQL migrations
├── docker/
│   ├── Dockerfile                  Multi-stage: builder → Tomcat 10.1
│   ├── server.xml                  Tuned Tomcat connector config
│   └── docker-compose.yml          Full local stack
├── k8s/                            Kubernetes manifests (deploy/svc/hpa/ingress)
├── .github/workflows/ci-cd.yml     GitHub Actions CI/CD pipeline
├── monitoring/
│   ├── prometheus.yml              Scrape config
│   ├── alert-rules.yml             8 alert rules (SLA, error rate, JVM, DB)
│   ├── otel-collector.yml          OpenTelemetry collector config
│   └── grafana/                    Dashboard JSON + provisioning
├── elk/
│   ├── logstash.conf               Log parsing pipeline
│   └── elasticsearch-index-template.json
├── runbooks/
│   ├── workflow-stuck.md
│   ├── db-failure.md
│   └── service-crash.md
├── scripts/
│   ├── backup.sh                   pg_dump → S3, 30/12/3 retention
│   └── restore.sh                  S3 → restore with scale-down/up
└── docs/ARCHITECTURE.md            Full system architecture reference
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.3 |
| Runtime | Apache Tomcat 10.1 (WAR deployment) |
| Database | PostgreSQL 15 + Flyway migrations |
| Container | Docker (multi-stage build) |
| Orchestration | Kubernetes + HPA |
| CI/CD | GitHub Actions |
| Metrics | Prometheus + Micrometer + Grafana |
| Logging | ELK (Elasticsearch + Logstash + Kibana) |
| Tracing | Jaeger + OpenTelemetry |
| Alerting | Prometheus Alertmanager + PagerDuty/Slack |

---

## PEGA Concept Mapping

This project was designed to mirror PEGA Platform's core concepts using standard Java/Spring technologies.

| PEGA Concept | FlowOps Implementation | Technology |
|---|---|---|
| **Case** | `CustomerCase` entity | JPA / PostgreSQL |
| **Stage** | `CaseStatus` enum (OPEN → IN_REVIEW → APPROVED/REJECTED → CLOSED) | Java enum |
| **Flow Action** | `WorkflowRule` interface + rule chain | Chain of Responsibility |
| **Skill-Based Routing** | `RoutingService` — matches case type to agent skill + availability | Spring Service + JPQL |
| **Decision Table** | `case_routing_rules` DB table — no code change needed to add new case types | Flyway migration |
| **SLA Service** | Scheduled watchdog — flags breached and stuck cases every 5 min | Spring `@Scheduled` |
| **Pulse / Audit Trail** | `workflow_transitions` table — every state change recorded with actor + reason | JPA |
| **Operator Portal** | REST API (`POST /cases`, `POST /transitions`, `GET /cases?priority=CRITICAL`) | Spring MVC |
| **Workload Balancing** | Round-robin via `lastAssignedAt ASC` ordering | JPQL |
| **Urgency Override** | `CRITICAL` priority ignores agent capacity — SLA takes precedence | RoutingService |
| **Reporting** | Business metrics exposed to Prometheus, visualized in Grafana | Micrometer |
| **Log Management** | Structured JSON logs → Logstash → Elasticsearch → Kibana | ELK Stack |
| **Distributed Tracing** | Trace ID propagated across all services, visualized in Jaeger | OpenTelemetry |

### Key Design Decisions That Mirror PEGA

**Routing rules as data, not code** — Adding a new case type requires only an INSERT into `case_routing_rules`. No deployment needed. This mirrors how PEGA business analysts configure routing in Decision Tables without touching code.

**Rule engine over if-else** — The `WorkflowEngine` evaluates rules in priority order and stops on the first violation. This is how PEGA Flow Actions work: each action has pre-conditions that are evaluated sequentially.

**CRITICAL priority overrides capacity** — When SLA is 2 hours, the system routes to the best available expert regardless of their current workload. This reflects how PEGA handles urgent cases with SLA escalation policies.
