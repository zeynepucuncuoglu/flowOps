package com.telecom.casemanagement.service;

import com.telecom.casemanagement.dto.CaseResponse;
import com.telecom.casemanagement.dto.CaseSearchRequest;
import com.telecom.casemanagement.dto.CreateCaseRequest;
import com.telecom.casemanagement.dto.TransitionRequest;
import com.telecom.casemanagement.engine.WorkflowEngine;
import com.telecom.casemanagement.exception.CaseNotFoundException;
import com.telecom.casemanagement.exception.InvalidTransitionException;
import com.telecom.casemanagement.model.CasePriority;
import com.telecom.casemanagement.model.CaseStatus;
import com.telecom.casemanagement.model.CaseType;
import com.telecom.casemanagement.model.CustomerCase;
import com.telecom.casemanagement.repository.CustomerCaseRepository;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.observation.annotation.Observed;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class CaseService {

    private final CustomerCaseRepository caseRepository;
    private final WorkflowEngine workflowEngine;
    private final CaseNumberGenerator caseNumberGenerator;
    private final RoutingService routingService;
    private final MeterRegistry meterRegistry;

    // Live gauge — Prometheus scrapes this to show open case count in real time
    private final AtomicInteger openCaseGauge = new AtomicInteger(0);

    @PostConstruct
    void registerGauges() {
        Gauge.builder("cases.open.count", openCaseGauge, AtomicInteger::get)
             .description("Number of currently OPEN cases")
             .register(meterRegistry);
    }

    @Observed(name = "cases.create", contextualName = "case-create")
    @Transactional
    @Timed(value = "cases.create", description = "Time to create a new case")
    public CaseResponse createCase(CreateCaseRequest req, String createdBy) {
        // Auto-route: find the best available agent before saving
        String assignedAgent = routingService
                .findBestAgent(req.getCaseType(), req.getPriority())
                .orElse(null); // null = no agent available → stays OPEN in supervisor queue

        CustomerCase c = CustomerCase.builder()
                .caseNumber(caseNumberGenerator.next())
                .customerId(req.getCustomerId())
                .customerName(req.getCustomerName())
                .status(CaseStatus.OPEN)
                .priority(req.getPriority())
                .caseType(req.getCaseType())
                .subject(req.getSubject())
                .description(req.getDescription())
                .slaDueAt(LocalDateTime.now().plusHours(req.getPriority().getSlaHours()))
                .assignedAgentId(assignedAgent)
                .build();

        c = caseRepository.save(c);
        openCaseGauge.incrementAndGet();

        if (assignedAgent != null) {
            log.info("Case created + routed: caseNumber={} type={} priority={} assignedTo={} by={}",
                    c.getCaseNumber(), c.getCaseType(), c.getPriority(), assignedAgent, createdBy);
        } else {
            log.warn("Case created UNROUTED: caseNumber={} type={} priority={} — no agent available, supervisor queue",
                    c.getCaseNumber(), c.getCaseType(), c.getPriority());
            meterRegistry.counter("routing.unrouted",
                    "caseType", req.getCaseType().name(),
                    "priority", req.getPriority().name()).increment();
        }

        return toResponse(c);
    }

    @Transactional
    @Timed(value = "cases.transition", description = "Time to apply a workflow transition")
    public CaseResponse transition(UUID caseId, TransitionRequest req, String performedBy) {
        // Pessimistic lock: prevents two agents transitioning the same case simultaneously
        CustomerCase c = caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));

        try {
            workflowEngine.transition(c, req.getTargetStatus(), performedBy, req.getReason());
        } catch (Exception ex) {
            meterRegistry.counter("workflow.transitions.rejected",
                    "reason", ex.getClass().getSimpleName()).increment();
            throw new InvalidTransitionException(ex.getMessage());
        }

        if (req.getTargetStatus() == CaseStatus.CLOSED || req.getTargetStatus() == CaseStatus.REJECTED) {
            c.setResolvedAt(LocalDateTime.now());
            openCaseGauge.decrementAndGet();
        }

        c = caseRepository.save(c);

        log.info("Case transitioned: caseNumber={} -> {} by={}",
                c.getCaseNumber(), req.getTargetStatus(), performedBy);

        return toResponse(c);
    }

    @Transactional(readOnly = true)
    public CaseResponse getCase(UUID caseId) {
        return caseRepository.findById(caseId)
                .map(this::toResponse)
                .orElseThrow(() -> new CaseNotFoundException(caseId));
    }

    @Transactional(readOnly = true)
    public Page<CaseResponse> searchCases(CaseSearchRequest req) {
        Pageable pageable = PageRequest.of(req.getPage(), req.getSize(),
                Sort.by(Sort.Direction.fromString(req.getSortDir()), req.getSortBy()));

        // JpaSpecificationExecutor provides dynamic filter composition
        return caseRepository.findAll(CaseSpecifications.build(req), pageable)
                             .map(this::toResponse);
    }

    @Transactional
    public CaseResponse assignCase(UUID caseId, String agentId, String performedBy) {
        CustomerCase c = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));
        c.setAssignedAgentId(agentId);
        c = caseRepository.save(c);
        log.info("Case assigned: caseNumber={} agentId={} by={}", c.getCaseNumber(), agentId, performedBy);
        return toResponse(c);
    }

    /** Scheduled SLA watchdog — fires every 5 minutes, flags breached cases. */
    @Scheduled(fixedRateString = "${flowops.sla.check-interval-ms:300000}")
    @Transactional(readOnly = true)
    public void checkSlaBreaches() {
        List<CustomerCase> breached = caseRepository.findSlaBreachedCases(LocalDateTime.now());
        if (!breached.isEmpty()) {
            log.warn("SLA_BREACH detected: {} cases overdue", breached.size());
            meterRegistry.gauge("cases.sla.breached.count", breached.size());
            breached.forEach(c ->
                log.warn("SLA_BREACH caseNumber={} priority={} slaDue={} status={}",
                    c.getCaseNumber(), c.getPriority(), c.getSlaDueAt(), c.getStatus())
            );
        }
    }

    /** Detects workflow-stuck cases (in IN_REVIEW for more than configured hours). */
    @Scheduled(fixedRateString = "${flowops.stuck.check-interval-ms:600000}")
    @Transactional(readOnly = true)
    public void detectStuckWorkflows() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(4);
        List<CustomerCase> stuck = caseRepository.findStuckInReviewCases(threshold);
        if (!stuck.isEmpty()) {
            log.warn("STUCK_WORKFLOW: {} cases in IN_REVIEW for >4h", stuck.size());
            meterRegistry.gauge("cases.stuck.count", stuck.size());
            stuck.forEach(c ->
                log.warn("STUCK_WORKFLOW caseNumber={} updatedAt={} assignedAgent={}",
                    c.getCaseNumber(), c.getUpdatedAt(), c.getAssignedAgentId())
            );
        }
    }

    private CaseResponse toResponse(CustomerCase c) {
        List<CaseResponse.TransitionSummary> recentTransitions = c.getTransitions().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(5)
                .map(t -> CaseResponse.TransitionSummary.builder()
                        .fromStatus(t.getFromStatus())
                        .toStatus(t.getToStatus())
                        .performedBy(t.getPerformedBy())
                        .reason(t.getReason())
                        .at(t.getCreatedAt())
                        .build())
                .toList();

        return CaseResponse.builder()
                .id(c.getId())
                .caseNumber(c.getCaseNumber())
                .customerId(c.getCustomerId())
                .customerName(c.getCustomerName())
                .status(c.getStatus())
                .priority(c.getPriority())
                .caseType(c.getCaseType())
                .subject(c.getSubject())
                .description(c.getDescription())
                .assignedAgentId(c.getAssignedAgentId())
                .slaDueAt(c.getSlaDueAt())
                .resolvedAt(c.getResolvedAt())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .version(c.getVersion() != null ? c.getVersion() : 0)
                .recentTransitions(recentTransitions)
                .build();
    }
}
