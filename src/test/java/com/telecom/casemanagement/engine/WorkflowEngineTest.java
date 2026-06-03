package com.telecom.casemanagement.engine;

import com.telecom.casemanagement.engine.rules.CriticalCaseSlaRule;
import com.telecom.casemanagement.engine.rules.FraudCaseApprovalRule;
import com.telecom.casemanagement.engine.rules.ValidStatusTransitionRule;
import com.telecom.casemanagement.model.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowEngineTest {

    private WorkflowEngine workflowEngine;

    @BeforeEach
    void setUp() {
        List<WorkflowRule> rules = List.of(
                new ValidStatusTransitionRule(),
                new FraudCaseApprovalRule(),
                new CriticalCaseSlaRule()
        );
        workflowEngine = new WorkflowEngine(rules, new SimpleMeterRegistry());
    }

    private CustomerCase buildCase(CaseStatus status, CasePriority priority, CaseType caseType) {
        CustomerCase c = new CustomerCase();
        c.setId(UUID.randomUUID());
        c.setStatus(status);
        c.setPriority(priority);
        c.setCaseType(caseType);
        c.setSlaDueAt(LocalDateTime.now().plusHours(8));
        c.setTransitions(new ArrayList<>());
        return c;
    }

    @Test
    void transition_succeeds_forValidTransition() {
        CustomerCase c = buildCase(CaseStatus.OPEN, CasePriority.HIGH, CaseType.BILLING_DISPUTE);

        WorkflowTransition result = workflowEngine.transition(c, CaseStatus.IN_REVIEW, "agent1", "starting review");

        assertThat(c.getStatus()).isEqualTo(CaseStatus.IN_REVIEW);
        assertThat(result.getFromStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(result.getToStatus()).isEqualTo(CaseStatus.IN_REVIEW);
        assertThat(result.getPerformedBy()).isEqualTo("agent1");
    }

    @Test
    void transition_throws_forInvalidTransition() {
        CustomerCase c = buildCase(CaseStatus.OPEN, CasePriority.HIGH, CaseType.BILLING_DISPUTE);

        assertThatThrownBy(() -> workflowEngine.transition(c, CaseStatus.CLOSED, "agent1", "trying to close"))
                .isInstanceOf(WorkflowRuleViolationException.class)
                .hasMessageContaining("OPEN")
                .hasMessageContaining("CLOSED");
    }

    @Test
    void transition_recordsAuditTrail() {
        CustomerCase c = buildCase(CaseStatus.OPEN, CasePriority.LOW, CaseType.PORT_REQUEST);

        workflowEngine.transition(c, CaseStatus.IN_REVIEW, "supervisor", "assigned");

        assertThat(c.getTransitions()).hasSize(1);
        assertThat(c.getTransitions().get(0).getRuleApplied()).contains("VALID_STATUS_TRANSITION");
    }

    @Test
    void transition_allowsRejected_toOpen_asReopenPath() {
        CustomerCase c = buildCase(CaseStatus.REJECTED, CasePriority.LOW, CaseType.BILLING_DISPUTE);

        workflowEngine.transition(c, CaseStatus.OPEN, "agent", "reopen after rejection");

        assertThat(c.getStatus()).isEqualTo(CaseStatus.OPEN);
    }

    @Test
    void transition_blocksClosed_toAnyStatus() {
        CustomerCase c = buildCase(CaseStatus.CLOSED, CasePriority.LOW, CaseType.BILLING_DISPUTE);

        assertThatThrownBy(() -> workflowEngine.transition(c, CaseStatus.OPEN, "agent", "reopen closed"))
                .isInstanceOf(WorkflowRuleViolationException.class);
    }
}
