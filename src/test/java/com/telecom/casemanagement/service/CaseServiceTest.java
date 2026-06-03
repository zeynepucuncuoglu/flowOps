package com.telecom.casemanagement.service;

import com.telecom.casemanagement.dto.CaseResponse;
import com.telecom.casemanagement.dto.CreateCaseRequest;
import com.telecom.casemanagement.engine.WorkflowEngine;
import com.telecom.casemanagement.model.*;
import com.telecom.casemanagement.repository.CustomerCaseRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CaseServiceTest {

    @Mock
    private CustomerCaseRepository caseRepository;

    @Mock
    private WorkflowEngine workflowEngine;

    @Mock
    private CaseNumberGenerator caseNumberGenerator;

    @Mock
    private RoutingService routingService;

    private CaseService caseService;

    @BeforeEach
    void setUp() {
        caseService = new CaseService(
                caseRepository, workflowEngine, caseNumberGenerator,
                routingService, new SimpleMeterRegistry()
        );
    }

    private CustomerCase buildSavedCase(String caseNumber, String agentId) {
        CustomerCase c = new CustomerCase();
        c.setId(UUID.randomUUID());
        c.setCaseNumber(caseNumber);
        c.setCustomerId("CUST-001");
        c.setCustomerName("Test User");
        c.setStatus(CaseStatus.OPEN);
        c.setPriority(CasePriority.HIGH);
        c.setCaseType(CaseType.BILLING_DISPUTE);
        c.setSubject("Test subject");
        c.setSlaDueAt(LocalDateTime.now().plusHours(8));
        c.setAssignedAgentId(agentId);
        c.setTransitions(new ArrayList<>());
        return c;
    }

    @Test
    void createCase_assignsAgent_whenAvailable() {
        CreateCaseRequest req = new CreateCaseRequest();
        req.setCustomerId("CUST-001");
        req.setCustomerName("Test User");
        req.setCaseType(CaseType.BILLING_DISPUTE);
        req.setPriority(CasePriority.HIGH);
        req.setSubject("Test subject");
        req.setDescription("Test description");

        when(caseNumberGenerator.next()).thenReturn("CASE-2026-00001");
        when(routingService.findBestAgent(CaseType.BILLING_DISPUTE, CasePriority.HIGH))
                .thenReturn(Optional.of("AGT-001"));
        when(caseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CaseResponse response = caseService.createCase(req, "admin");

        assertThat(response.getAssignedAgentId()).isEqualTo("AGT-001");
        assertThat(response.getStatus()).isEqualTo(CaseStatus.OPEN);
    }

    @Test
    void createCase_leavesUnassigned_whenNoAgentAvailable() {
        CreateCaseRequest req = new CreateCaseRequest();
        req.setCustomerId("CUST-002");
        req.setCustomerName("Test User 2");
        req.setCaseType(CaseType.BILLING_DISPUTE);
        req.setPriority(CasePriority.LOW);
        req.setSubject("Subject");
        req.setDescription("Description");

        when(caseNumberGenerator.next()).thenReturn("CASE-2026-00002");
        when(routingService.findBestAgent(any(), any())).thenReturn(Optional.empty());
        when(caseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CaseResponse response = caseService.createCase(req, "admin");

        assertThat(response.getAssignedAgentId()).isNull();
    }

    @Test
    void getCase_returnsCase_whenExists() {
        UUID id = UUID.randomUUID();
        CustomerCase c = buildSavedCase("CASE-2026-00001", "AGT-001");
        c.setId(id);

        when(caseRepository.findById(id)).thenReturn(Optional.of(c));

        CaseResponse response = caseService.getCase(id);

        assertThat(response.getCaseNumber()).isEqualTo("CASE-2026-00001");
        assertThat(response.getAssignedAgentId()).isEqualTo("AGT-001");
    }
}
