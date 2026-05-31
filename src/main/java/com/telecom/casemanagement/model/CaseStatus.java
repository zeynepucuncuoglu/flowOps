package com.telecom.casemanagement.model;

public enum CaseStatus {
    OPEN,
    IN_REVIEW,
    APPROVED,
    REJECTED,
    CLOSED;

    public boolean canTransitionTo(CaseStatus target) {
        return switch (this) {
            case OPEN      -> target == IN_REVIEW;
            case IN_REVIEW -> target == APPROVED || target == REJECTED;
            case APPROVED  -> target == CLOSED;
            case REJECTED  -> target == CLOSED || target == OPEN; // re-open path
            case CLOSED    -> false;
        };
    }
}
