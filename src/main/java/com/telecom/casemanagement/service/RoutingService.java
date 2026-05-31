package com.telecom.casemanagement.service;

import com.telecom.casemanagement.model.Agent;
import com.telecom.casemanagement.model.AgentSkill;
import com.telecom.casemanagement.model.CasePriority;
import com.telecom.casemanagement.model.CaseRoutingRule;
import com.telecom.casemanagement.model.CaseType;
import com.telecom.casemanagement.repository.AgentRepository;
import com.telecom.casemanagement.repository.CaseRoutingRuleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Skill-Based Routing Engine.
 *
 * Normal flow:
 *   1. Look up the required skill for the given CaseType (cached)
 *   2. Find online agents who have that skill at or above min level
 *      AND whose active case count < their max capacity
 *   3. Among those, pick the one who waited longest (last_assigned_at ASC)
 *      — this is the fairness / round-robin mechanism
 *
 * CRITICAL override:
 *   Step 2 ignores capacity. The best available expert gets the case
 *   regardless of workload, because a 2-hour SLA cannot wait for a free slot.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoutingService {

    private final AgentRepository agentRepository;
    private final CaseRoutingRuleRepository routingRuleRepository;
    private final MeterRegistry meterRegistry;

    /**
     * Returns the best agent ID for a given case, or empty if no one is available.
     */
    @Observed(name = "routing.findBestAgent", contextualName = "routing-find-best-agent")
    @Transactional
    public Optional<String> findBestAgent(CaseType caseType, CasePriority priority) {

        // Step 1 — look up routing rule for this case type
        Optional<CaseRoutingRule> ruleOpt = routingRuleRepository.findByCaseType(caseType.name());
        if (ruleOpt.isEmpty()) {
            log.warn("No routing rule defined for caseType={} — case will be unassigned", caseType);
            meterRegistry.counter("routing.no_rule", "caseType", caseType.name()).increment();
            return Optional.empty();
        }

        CaseRoutingRule rule = ruleOpt.get();
        String requiredSkill = rule.getRequiredSkill();
        int minLevel         = rule.getMinSkillLevel();

        // Step 2 — find candidate agents
        List<Agent> candidates;

        if (priority == CasePriority.CRITICAL) {
            // CRITICAL: ignore capacity — SLA cannot wait
            candidates = agentRepository.findOnlineBySkillIgnoreCapacity(requiredSkill, minLevel);
            log.info("CRITICAL routing: skill={} minLevel={} candidates={}",
                    requiredSkill, minLevel, candidates.size());
        } else {
            // Normal: only agents with room in their queue
            candidates = agentRepository.findAvailableBySkill(requiredSkill, minLevel);
            log.info("Normal routing: skill={} minLevel={} priority={} candidates={}",
                    requiredSkill, minLevel, priority, candidates.size());
        }

        if (candidates.isEmpty()) {
            log.warn("No available agent for skill={} priority={} — case will be unassigned",
                    requiredSkill, priority);
            meterRegistry.counter("routing.no_agent_available",
                    "skill", requiredSkill,
                    "priority", priority.name()).increment();
            return Optional.empty();
        }

        // Step 3 — pick first (query already sorted: skill DESC, last_assigned ASC)
        Agent chosen = candidates.get(0);

        // Update last_assigned_at so next case goes to someone else (fairness)
        agentRepository.updateLastAssignedAt(chosen.getAgentId(), LocalDateTime.now());

        log.info("Routing decision: caseType={} priority={} → agentId={} skill_level={} team={}",
                caseType, priority, chosen.getAgentId(),
                chosen.getSkills().stream()
                      .filter(s -> s.getId().getSkill().equals(requiredSkill))
                      .mapToInt(AgentSkill::getSkillLevel).max().orElse(0),
                chosen.getTeam());

        meterRegistry.counter("routing.assigned",
                "caseType", caseType.name(),
                "priority", priority.name(),
                "agentId",  chosen.getAgentId()).increment();

        return Optional.of(chosen.getAgentId());
    }
}
