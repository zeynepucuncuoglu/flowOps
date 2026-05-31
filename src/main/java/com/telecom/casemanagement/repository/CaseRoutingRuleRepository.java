package com.telecom.casemanagement.repository;

import com.telecom.casemanagement.model.CaseRoutingRule;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CaseRoutingRuleRepository extends JpaRepository<CaseRoutingRule, String> {

    // Routing rules are static config — cache them to avoid DB hit on every case creation
    @Cacheable("routing-rules")
    Optional<CaseRoutingRule> findByCaseType(String caseType);
}
