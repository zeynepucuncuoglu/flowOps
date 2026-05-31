package com.telecom.casemanagement.engine;

import com.telecom.casemanagement.model.CaseStatus;
import com.telecom.casemanagement.model.CustomerCase;
import com.telecom.casemanagement.model.WorkflowTransition;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Core workflow engine. Evaluates all registered rules in priority order,
 * applies the transition, and records an audit trail entry.
 *
 * Analogous to PEGA's "Flow Action" processor — rule chain → state change → audit.
 */
@Component
@Slf4j
public class WorkflowEngine {

    private final List<WorkflowRule> rules;
    private final MeterRegistry meterRegistry;

    public WorkflowEngine(List<WorkflowRule> rules, MeterRegistry meterRegistry) {
        this.rules = rules.stream()
                          .sorted(Comparator.comparingInt(WorkflowRule::getPriority))
                          .toList();
        this.meterRegistry = meterRegistry;
    }

    /**
     * Attempt to transition {@code caseEntity} to {@code targetStatus}.
     *
     * @param performedBy  agent/system identifier for audit
     * @param reason       free-text reason logged in the transition record
     * @return the recorded {@link WorkflowTransition}
     * @throws WorkflowRuleViolationException if any rule blocks the transition
     */
    public WorkflowTransition transition(CustomerCase caseEntity,
                                          CaseStatus targetStatus,
                                          String performedBy,
                                          String reason) {
        CaseStatus fromStatus = caseEntity.getStatus();
        String appliedRules = evaluateRules(caseEntity, targetStatus);

        Timer.Sample sample = Timer.start(meterRegistry);

        caseEntity.setStatus(targetStatus);

        WorkflowTransition transition = WorkflowTransition.builder()
                .customerCase(caseEntity)
                .fromStatus(fromStatus)
                .toStatus(targetStatus)
                .performedBy(performedBy)
                .reason(reason)
                .ruleApplied(appliedRules)
                .build();

        caseEntity.getTransitions().add(transition);

        sample.stop(meterRegistry.timer("workflow.transition.duration",
                "from", fromStatus.name(),
                "to", targetStatus.name()));

        meterRegistry.counter("workflow.transitions.total",
                "from", fromStatus.name(),
                "to", targetStatus.name()).increment();

        log.info("Workflow transition: caseId={} {} -> {} by={}",
                caseEntity.getId(), fromStatus, targetStatus, performedBy);

        return transition;
    }

    // Returns names of rules that were checked (all passed)
    private String evaluateRules(CustomerCase c, CaseStatus target) {
        StringBuilder applied = new StringBuilder();
        for (WorkflowRule rule : rules) {
            rule.evaluate(c, target); // throws WorkflowRuleViolationException on failure
            if (!applied.isEmpty()) applied.append(", ");
            applied.append(rule.getName());
        }
        return applied.toString();
    }
}
