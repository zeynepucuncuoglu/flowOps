package com.telecom.casemanagement.repository;

import com.telecom.casemanagement.model.WorkflowTransition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface WorkflowTransitionRepository extends JpaRepository<WorkflowTransition, UUID> {

    List<WorkflowTransition> findByCustomerCaseIdOrderByCreatedAtDesc(UUID caseId);

    @Query("""
           SELECT t FROM WorkflowTransition t
           WHERE t.customerCase.id = :caseId
           ORDER BY t.createdAt DESC
           LIMIT :limit
           """)
    List<WorkflowTransition> findRecentByCaseId(@Param("caseId") UUID caseId,
                                                 @Param("limit") int limit);

    // Used for throughput metrics: how many transitions occurred in a window
    @Query("""
           SELECT COUNT(t) FROM WorkflowTransition t
           WHERE t.createdAt BETWEEN :from AND :to
           """)
    long countTransitionsInWindow(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
