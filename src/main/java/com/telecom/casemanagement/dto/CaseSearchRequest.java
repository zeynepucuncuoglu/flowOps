package com.telecom.casemanagement.dto;

import com.telecom.casemanagement.model.CasePriority;
import com.telecom.casemanagement.model.CaseStatus;
import com.telecom.casemanagement.model.CaseType;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Data
public class CaseSearchRequest {
    private String customerId;
    private CaseStatus status;
    private CasePriority priority;
    private CaseType caseType;
    private String assignedAgentId;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime createdAfter;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime createdBefore;

    private boolean slaBreached;

    private int page = 0;
    private int size = 20;
    private String sortBy = "createdAt";
    private String sortDir = "desc";
}
