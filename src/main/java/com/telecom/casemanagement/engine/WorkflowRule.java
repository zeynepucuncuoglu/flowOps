package com.telecom.casemanagement.engine;

import com.telecom.casemanagement.model.CustomerCase;
import com.telecom.casemanagement.model.CaseStatus;

/**
 * A single named rule that can block or force a workflow transition.
 * Rules are evaluated in priority order; first failing/forcing rule wins.
 */
public interface WorkflowRule {

    String getName();

    int getPriority(); // lower = evaluated first

    /**
     * Called before a transition is applied.
     * Throw {@link WorkflowRuleViolationException} to block the transition.
     */
    void evaluate(CustomerCase caseEntity, CaseStatus targetStatus);
}
