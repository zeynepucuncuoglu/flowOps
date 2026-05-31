package com.telecom.casemanagement.engine.rules;

import com.telecom.casemanagement.engine.WorkflowRule;
import com.telecom.casemanagement.engine.WorkflowRuleViolationException;
import com.telecom.casemanagement.model.CaseStatus;
import com.telecom.casemanagement.model.CustomerCase;
import org.springframework.stereotype.Component;

/**
 * Guard rail: rejects any transition not allowed by the state machine.
 * This is evaluated first (priority 1) so all other rules can assume the
 * transition is structurally valid.
 */
@Component
public class ValidStatusTransitionRule implements WorkflowRule {

    @Override
    public String getName() { return "VALID_STATUS_TRANSITION"; }

    @Override
    public int getPriority() { return 1; }

    @Override
    public void evaluate(CustomerCase c, CaseStatus target) {
        if (!c.getStatus().canTransitionTo(target)) {
            throw new WorkflowRuleViolationException(
                getName(),
                String.format("Transition from %s to %s is not allowed", c.getStatus(), target)
            );
        }
    }
}
