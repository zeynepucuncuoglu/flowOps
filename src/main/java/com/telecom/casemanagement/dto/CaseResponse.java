package com.telecom.casemanagement.dto;

import com.telecom.casemanagement.model.CasePriority;
import com.telecom.casemanagement.model.CaseStatus;
import com.telecom.casemanagement.model.CaseType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class CaseResponse {
    private UUID id;
    private String caseNumber;
    private String customerId;
    private String customerName;
    private CaseStatus status;
    private CasePriority priority;
    private CaseType caseType;
    private String subject;
    private String description;
    private String assignedAgentId;
    private LocalDateTime slaDueAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private long version;
    private List<TransitionSummary> recentTransitions;

    @Data
    @Builder
    public static class TransitionSummary {
        private CaseStatus fromStatus;
        private CaseStatus toStatus;
        private String performedBy;
        private String reason;
        private LocalDateTime at;
    }
}
