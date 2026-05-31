package com.telecom.casemanagement.exception;

import java.util.UUID;

public class CaseNotFoundException extends RuntimeException {
    public CaseNotFoundException(UUID id) {
        super("Case not found: " + id);
    }
}
