package com.telecom.casemanagement.engine.rules;

import com.telecom.casemanagement.engine.WorkflowRule;
import com.telecom.casemanagement.engine.WorkflowRuleViolationException;
import com.telecom.casemanagement.model.CasePriority;
import com.telecom.casemanagement.model.CaseStatus;
import com.telecom.casemanagement.model.CustomerCase;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * CRITICAL cases cannot be closed without a resolution note.
 * Also warns (but does not block) when SLA is already breached at closing time.
 */
@Component
public class CriticalCaseSlaRule implements WorkflowRule {

    @Override
    public String getName() { return "CRITICAL_CASE_RESOLUTION_REQUIRED"; }

    @Override
    public int getPriority() { return 20; }

    @Override
    public void evaluate(CustomerCase c, CaseStatus target) {
        if (c.getPriority() == CasePriority.CRITICAL && target == CaseStatus.CLOSED) {
            if (c.getResolutionNotes() == null || c.getResolutionNotes().isBlank()) {
                throw new WorkflowRuleViolationException(
                    getName(),
                    "CRITICAL cases require resolution_notes before closing"
                );
            }
        }
    }
}
