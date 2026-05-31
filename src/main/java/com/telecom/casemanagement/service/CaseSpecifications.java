package com.telecom.casemanagement.service;

import com.telecom.casemanagement.dto.CaseSearchRequest;
import com.telecom.casemanagement.model.CustomerCase;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class CaseSpecifications {

    private CaseSpecifications() {}

    public static Specification<CustomerCase> build(CaseSearchRequest req) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (req.getCustomerId() != null)
                predicates.add(cb.equal(root.get("customerId"), req.getCustomerId()));

            if (req.getStatus() != null)
                predicates.add(cb.equal(root.get("status"), req.getStatus()));

            if (req.getPriority() != null)
                predicates.add(cb.equal(root.get("priority"), req.getPriority()));

            if (req.getCaseType() != null)
                predicates.add(cb.equal(root.get("caseType"), req.getCaseType()));

            if (req.getAssignedAgentId() != null)
                predicates.add(cb.equal(root.get("assignedAgentId"), req.getAssignedAgentId()));

            if (req.getCreatedAfter() != null)
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), req.getCreatedAfter()));

            if (req.getCreatedBefore() != null)
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), req.getCreatedBefore()));

            if (req.isSlaBreached())
                predicates.add(cb.lessThan(root.get("slaDueAt"), LocalDateTime.now()));

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
