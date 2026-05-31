package com.telecom.casemanagement.model;

public enum CasePriority {
    LOW, MEDIUM, HIGH, CRITICAL;

    /** SLA deadline in hours from case creation */
    public int getSlaHours() {
        return switch (this) {
            case LOW      -> 72;
            case MEDIUM   -> 24;
            case HIGH     -> 8;
            case CRITICAL -> 2;
        };
    }
}
