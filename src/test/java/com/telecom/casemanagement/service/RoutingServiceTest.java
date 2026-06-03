package com.telecom.casemanagement.service;

import com.telecom.casemanagement.model.Agent;
import com.telecom.casemanagement.model.CasePriority;
import com.telecom.casemanagement.model.CaseRoutingRule;
import com.telecom.casemanagement.model.CaseType;
import com.telecom.casemanagement.repository.AgentRepository;
import com.telecom.casemanagement.repository.CaseRoutingRuleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoutingServiceTest {

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private CaseRoutingRuleRepository routingRuleRepository;

    private MeterRegistry meterRegistry;
    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        routingService = new RoutingService(agentRepository, routingRuleRepository, meterRegistry);
    }

    @Test
    void findBestAgent_returnsEmpty_whenNoRoutingRuleExists() {
        when(routingRuleRepository.findByCaseType(anyString())).thenReturn(Optional.empty());

        Optional<String> result = routingService.findBestAgent(CaseType.BILLING_DISPUTE, CasePriority.HIGH);

        assertThat(result).isEmpty();
        verify(agentRepository, never()).findAvailableBySkill(any(), anyInt());
    }

    @Test
    void findBestAgent_usesNormalQuery_forHighPriority() {
        CaseRoutingRule rule = new CaseRoutingRule("BILLING_DISPUTE", "billing", 1);
        when(routingRuleRepository.findByCaseType("BILLING_DISPUTE")).thenReturn(Optional.of(rule));

        Agent agent = new Agent();
        agent.setAgentId("AGT-001");
        agent.setSkills(Collections.emptySet());
        when(agentRepository.findAvailableBySkill("billing", 1)).thenReturn(List.of(agent));

        Optional<String> result = routingService.findBestAgent(CaseType.BILLING_DISPUTE, CasePriority.HIGH);

        assertThat(result).contains("AGT-001");
        verify(agentRepository).findAvailableBySkill("billing", 1);
        verify(agentRepository, never()).findOnlineBySkillIgnoreCapacity(any(), anyInt());
    }

    @Test
    void findBestAgent_ignoresCapacity_forCriticalPriority() {
        CaseRoutingRule rule = new CaseRoutingRule("FRAUD_ALERT", "fraud_investigation", 2);
        when(routingRuleRepository.findByCaseType("FRAUD_ALERT")).thenReturn(Optional.of(rule));

        Agent agent = new Agent();
        agent.setAgentId("AGT-002");
        agent.setSkills(Collections.emptySet());
        when(agentRepository.findOnlineBySkillIgnoreCapacity("fraud_investigation", 2)).thenReturn(List.of(agent));

        Optional<String> result = routingService.findBestAgent(CaseType.FRAUD_ALERT, CasePriority.CRITICAL);

        assertThat(result).contains("AGT-002");
        verify(agentRepository).findOnlineBySkillIgnoreCapacity("fraud_investigation", 2);
        verify(agentRepository, never()).findAvailableBySkill(any(), anyInt());
    }

    @Test
    void findBestAgent_returnsEmpty_whenNoAgentsAvailable() {
        CaseRoutingRule rule = new CaseRoutingRule("BILLING_DISPUTE", "billing", 1);
        when(routingRuleRepository.findByCaseType("BILLING_DISPUTE")).thenReturn(Optional.of(rule));
        when(agentRepository.findAvailableBySkill("billing", 1)).thenReturn(Collections.emptyList());

        Optional<String> result = routingService.findBestAgent(CaseType.BILLING_DISPUTE, CasePriority.MEDIUM);

        assertThat(result).isEmpty();
    }

    @Test
    void findBestAgent_updatesLastAssignedAt_afterAssignment() {
        CaseRoutingRule rule = new CaseRoutingRule("BILLING_DISPUTE", "billing", 1);
        when(routingRuleRepository.findByCaseType("BILLING_DISPUTE")).thenReturn(Optional.of(rule));

        Agent agent = new Agent();
        agent.setAgentId("AGT-001");
        agent.setSkills(Collections.emptySet());
        when(agentRepository.findAvailableBySkill("billing", 1)).thenReturn(List.of(agent));

        routingService.findBestAgent(CaseType.BILLING_DISPUTE, CasePriority.LOW);

        verify(agentRepository).updateLastAssignedAt(eq("AGT-001"), any());
    }
}
