package com.telecom.casemanagement.repository;

import com.telecom.casemanagement.model.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentRepository extends JpaRepository<Agent, String> {

    /**
     * Normal routing query — skill match + online + capacity available.
     * Ordered by: skill level DESC (senior first), then last_assigned_at ASC (longest waiting first).
     * The "longest waiting first" ordering is the round-robin fairness mechanism.
     */
    @Query("""
           SELECT a FROM Agent a
           JOIN a.skills s
           WHERE s.id.skill    = :skill
             AND s.skillLevel >= :minLevel
             AND a.online      = true
             AND (
               SELECT COUNT(c) FROM CustomerCase c
               WHERE c.assignedAgentId = a.agentId
                 AND c.status IN ('OPEN', 'IN_REVIEW')
             ) < a.maxCapacity
           ORDER BY
             s.skillLevel DESC,
             COALESCE(a.lastAssignedAt, CAST('2000-01-01' AS java.time.LocalDateTime)) ASC
           """)
    List<Agent> findAvailableBySkill(@Param("skill") String skill,
                                     @Param("minLevel") int minLevel);

    /**
     * CRITICAL override query — skill match + online, ignores capacity.
     * Ordered by skill level DESC only (best expert gets the critical case).
     * Used when SLA is tight (2h) and we can't afford to wait for a free slot.
     */
    @Query("""
           SELECT a FROM Agent a
           JOIN a.skills s
           WHERE s.id.skill    = :skill
             AND s.skillLevel >= :minLevel
             AND a.online      = true
           ORDER BY
             s.skillLevel DESC,
             COALESCE(a.lastAssignedAt, CAST('2000-01-01' AS java.time.LocalDateTime)) ASC
           """)
    List<Agent> findOnlineBySkillIgnoreCapacity(@Param("skill") String skill,
                                                 @Param("minLevel") int minLevel);

    @Modifying
    @Query("UPDATE Agent a SET a.lastAssignedAt = :now WHERE a.agentId = :agentId")
    void updateLastAssignedAt(@Param("agentId") String agentId,
                               @Param("now") LocalDateTime now);
}
