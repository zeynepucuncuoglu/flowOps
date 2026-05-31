package com.telecom.casemanagement.dto;

import com.telecom.casemanagement.model.CasePriority;
import com.telecom.casemanagement.model.CaseType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateCaseRequest {

    @NotBlank
    private String customerId;

    @NotBlank
    private String customerName;

    @NotNull
    private CaseType caseType;

    @NotNull
    private CasePriority priority;

    @NotBlank
    @Size(max = 200)
    private String subject;

    @Size(max = 4000)
    private String description;
}
