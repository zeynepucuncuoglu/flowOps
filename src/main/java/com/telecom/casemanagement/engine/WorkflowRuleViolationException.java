package com.telecom.casemanagement.engine;

public class WorkflowRuleViolationException extends RuntimeException {
    private final String ruleName;

    public WorkflowRuleViolationException(String ruleName, String message) {
        super(message);
        this.ruleName = ruleName;
    }

    public String getRuleName() {
        return ruleName;
    }
}
