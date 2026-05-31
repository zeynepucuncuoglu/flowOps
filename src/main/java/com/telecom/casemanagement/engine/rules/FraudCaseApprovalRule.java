package com.telecom.casemanagement.engine.rules;

import com.telecom.casemanagement.engine.WorkflowRule;
import com.telecom.casemanagement.engine.WorkflowRuleViolationException;
import com.telecom.casemanagement.model.CaseStatus;
import com.telecom.casemanagement.model.CaseType;
import com.telecom.casemanagement.model.CustomerCase;
import org.springframework.stereotype.Component;

/**
 * FRAUD_ALERT cases cannot be auto-approved; they must be assigned to an agent first.
 * Mirrors PEGA "when condition" — a business rule attached to a specific case type.
 */
@Component
public class FraudCaseApprovalRule implements WorkflowRule {

    @Override
    public String getName() { return "FRAUD_CASE_MUST_BE_ASSIGNED"; }

    @Override
    public int getPriority() { return 10; }

    @Override
    public void evaluate(CustomerCase c, CaseStatus target) {
        if (c.getCaseType() == CaseType.FRAUD_ALERT
                && target == CaseStatus.APPROVED
                && c.getAssignedAgentId() == null) {
            throw new WorkflowRuleViolationException(
                getName(),
                "Fraud cases must be assigned to an agent before approval"
            );
        }
    }
}
