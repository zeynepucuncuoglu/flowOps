package com.telecom.casemanagement.repository;

import com.telecom.casemanagement.model.CasePriority;
import com.telecom.casemanagement.model.CaseStatus;
import com.telecom.casemanagement.model.CustomerCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerCaseRepository
        extends JpaRepository<CustomerCase, UUID>, JpaSpecificationExecutor<CustomerCase> {

    Optional<CustomerCase> findByCaseNumber(String caseNumber);

    Page<CustomerCase> findByStatus(CaseStatus status, Pageable pageable);

    Page<CustomerCase> findByCustomerId(String customerId, Pageable pageable);

    Page<CustomerCase> findByAssignedAgentId(String agentId, Pageable pageable);

    // Pessimistic write lock for workflow transitions — prevents concurrent state changes
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CustomerCase c WHERE c.id = :id")
    Optional<CustomerCase> findByIdForUpdate(@Param("id") UUID id);

    // Cases whose SLA has been breached and are not yet closed
    @Query("SELECT c FROM CustomerCase c WHERE c.slaDueAt < :now AND c.status NOT IN ('CLOSED', 'REJECTED')")
    List<CustomerCase> findSlaBreachedCases(@Param("now") LocalDateTime now);

    // Dashboard aggregation: count per status
    @Query("SELECT c.status, COUNT(c) FROM CustomerCase c GROUP BY c.status")
    List<Object[]> countByStatus();

    // Cases stuck in IN_REVIEW beyond a threshold — used by SLA watchdog
    @Query("""
           SELECT c FROM CustomerCase c
           WHERE c.status = 'IN_REVIEW'
             AND c.updatedAt < :stuckThreshold
           """)
    List<CustomerCase> findStuckInReviewCases(@Param("stuckThreshold") LocalDateTime stuckThreshold);

    @Query("SELECT COUNT(c) FROM CustomerCase c WHERE c.status = :status AND c.priority = :priority")
    long countByStatusAndPriority(@Param("status") CaseStatus status,
                                   @Param("priority") CasePriority priority);
}
