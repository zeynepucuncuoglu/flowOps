package com.telecom.casemanagement.dto;

import com.telecom.casemanagement.model.CaseStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TransitionRequest {

    @NotNull
    private CaseStatus targetStatus;

    @Size(max = 2000)
    private String reason;
}
