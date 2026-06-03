-- FlowOps Schema V3 — Skill-Based Routing
-- Adds agent management + routing rule tables

-- ============================================================
-- AGENTS
-- ============================================================
CREATE TABLE IF NOT EXISTS flowops.agents (
    agent_id         VARCHAR(100) PRIMARY KEY,
    full_name        VARCHAR(200) NOT NULL,
    team             VARCHAR(100) NOT NULL,   -- 'fraud-team', 'billing-team', etc.
    is_online        BOOLEAN      NOT NULL DEFAULT false,
    max_capacity     INT          NOT NULL DEFAULT 5,   -- max concurrent active cases
    last_assigned_at TIMESTAMPTZ,                       -- null = never assigned
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agents_online ON flowops.agents (is_online);
CREATE INDEX IF NOT EXISTS idx_agents_team   ON flowops.agents (team);

-- ============================================================
-- AGENT SKILLS
-- ============================================================
CREATE TABLE IF NOT EXISTS flowops.agent_skills (
    agent_id    VARCHAR(100) NOT NULL REFERENCES flowops.agents(agent_id) ON DELETE CASCADE,
    skill       VARCHAR(100) NOT NULL,  -- 'fraud_investigation', 'billing', 'technical'
    skill_level INT          NOT NULL DEFAULT 1 CHECK (skill_level BETWEEN 1 AND 3),
    -- 1=basic, 2=intermediate, 3=expert (senior)
    PRIMARY KEY (agent_id, skill)
);

CREATE INDEX IF NOT EXISTS idx_agent_skills_skill ON flowops.agent_skills (skill, skill_level DESC);

-- ============================================================
-- CASE ROUTING RULES
-- Maps each CaseType to a required skill
-- ============================================================
CREATE TABLE IF NOT EXISTS flowops.case_routing_rules (
    case_type       VARCHAR(50) PRIMARY KEY,
    required_skill  VARCHAR(100) NOT NULL,
    min_skill_level INT          NOT NULL DEFAULT 1
);

-- Seed routing rules for all case types
INSERT INTO flowops.case_routing_rules (case_type, required_skill, min_skill_level) VALUES
    ('BILLING_DISPUTE',  'billing',              1),
    ('SERVICE_OUTAGE',   'technical',             1),
    ('ACCOUNT_CHANGE',   'account_management',    1),
    ('FRAUD_ALERT',      'fraud_investigation',   2),
    ('PORT_REQUEST',     'number_portability',    1),
    ('TECHNICAL_SUPPORT','technical',             1),
    ('CONTRACT_REVIEW',  'contract_management',   2)
ON CONFLICT DO NOTHING;

-- ============================================================
-- SEED: Sample agents for dev/staging
-- ============================================================
INSERT INTO flowops.agents (agent_id, full_name, team, is_online, max_capacity) VALUES
    ('AGT-001', 'Ahmet Yılmaz',   'billing-team',       true,  5),
    ('AGT-002', 'Zeynep Kaya',    'fraud-team',         true,  4),
    ('AGT-003', 'Mehmet Demir',   'fraud-team',         true,  4),
    ('AGT-004', 'Fatma Şahin',    'technical-team',     true,  5),
    ('AGT-005', 'Ali Çelik',      'billing-team',       false, 5),
    ('AGT-006', 'Ayşe Arslan',    'contract-team',      true,  3),
    ('AGT-007', 'Hasan Koç',      'technical-team',     true,  5)
ON CONFLICT DO NOTHING;

INSERT INTO flowops.agent_skills (agent_id, skill, skill_level) VALUES
    ('AGT-001', 'billing',              3),
    ('AGT-001', 'account_management',   2),
    ('AGT-002', 'fraud_investigation',  3),
    ('AGT-002', 'account_management',   1),
    ('AGT-003', 'fraud_investigation',  2),
    ('AGT-003', 'billing',              1),
    ('AGT-004', 'technical',            3),
    ('AGT-004', 'number_portability',   2),
    ('AGT-005', 'billing',              2),
    ('AGT-006', 'contract_management',  3),
    ('AGT-006', 'billing',              1),
    ('AGT-007', 'technical',            2),
    ('AGT-007', 'number_portability',   3)
ON CONFLICT DO NOTHING;

-- Auto-update trigger for agents.updated_at
DROP TRIGGER IF EXISTS trg_agents_updated_at ON flowops.agents;
CREATE TRIGGER trg_agents_updated_at
    BEFORE UPDATE ON flowops.agents
    FOR EACH ROW EXECUTE FUNCTION flowops.set_updated_at();
