package com.telecom.casemanagement.repository;

import com.telecom.casemanagement.model.CaseNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CaseNoteRepository extends JpaRepository<CaseNote, UUID> {

    List<CaseNote> findByCustomerCaseIdOrderByCreatedAtDesc(UUID caseId);

    List<CaseNote> findByCustomerCaseIdAndInternalFalseOrderByCreatedAtDesc(UUID caseId);
}
