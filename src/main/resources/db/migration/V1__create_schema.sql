-- FlowOps Schema V1 — Initial setup
-- All tables live in the "flowops" schema for multi-tenant isolation

CREATE SCHEMA IF NOT EXISTS flowops;

-- ============================================================
-- CUSTOMER CASES  (main work item, analogous to PEGA Case)
-- ============================================================
CREATE TABLE flowops.customer_cases (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    case_number       VARCHAR(20)  NOT NULL UNIQUE,
    customer_id       VARCHAR(100) NOT NULL,
    customer_name     VARCHAR(200) NOT NULL,
    status            VARCHAR(20)  NOT NULL CHECK (status IN ('OPEN','IN_REVIEW','APPROVED','REJECTED','CLOSED')),
    priority          VARCHAR(20)  NOT NULL CHECK (priority IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    case_type         VARCHAR(30)  NOT NULL,
    subject           VARCHAR(200) NOT NULL,
    description       TEXT,
    assigned_agent_id VARCHAR(100),
    sla_due_at        TIMESTAMPTZ,
    resolved_at       TIMESTAMPTZ,
    resolution_notes  TEXT,
    version           BIGINT       NOT NULL DEFAULT 0,  -- optimistic lock version
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Performance indexes for most common query patterns
CREATE INDEX idx_case_status      ON flowops.customer_cases (status);
CREATE INDEX idx_case_customer    ON flowops.customer_cases (customer_id);
CREATE INDEX idx_case_assigned    ON flowops.customer_cases (assigned_agent_id);
CREATE INDEX idx_case_created_at  ON flowops.customer_cases (created_at DESC);
CREATE INDEX idx_case_sla_due     ON flowops.customer_cases (sla_due_at) WHERE status NOT IN ('CLOSED','REJECTED');
-- Composite: dashboard queries filter by status + priority frequently
CREATE INDEX idx_case_status_priority ON flowops.customer_cases (status, priority);

-- ============================================================
-- WORKFLOW TRANSITIONS  (immutable audit trail)
-- ============================================================
CREATE TABLE flowops.workflow_transitions (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id      UUID        NOT NULL REFERENCES flowops.customer_cases(id) ON DELETE CASCADE,
    from_status  VARCHAR(20) NOT NULL,
    to_status    VARCHAR(20) NOT NULL,
    performed_by VARCHAR(100) NOT NULL,
    reason       TEXT,
    rule_applied VARCHAR(500),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_wt_case_id    ON flowops.workflow_transitions (case_id);
CREATE INDEX idx_wt_created_at ON flowops.workflow_transitions (created_at DESC);

-- ============================================================
-- CASE NOTES
-- ============================================================
CREATE TABLE flowops.case_notes (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id     UUID        NOT NULL REFERENCES flowops.customer_cases(id) ON DELETE CASCADE,
    author_id   VARCHAR(100) NOT NULL,
    content     TEXT        NOT NULL,
    is_internal BOOLEAN     NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notes_case_id ON flowops.case_notes (case_id);

-- ============================================================
-- AUTO-UPDATE updated_at trigger
-- ============================================================
CREATE OR REPLACE FUNCTION flowops.set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_cases_updated_at
    BEFORE UPDATE ON flowops.customer_cases
    FOR EACH ROW EXECUTE FUNCTION flowops.set_updated_at();
