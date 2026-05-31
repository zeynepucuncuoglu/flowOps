package com.telecom.casemanagement.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "agent_skills", schema = "flowops")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AgentSkill {

    @EmbeddedId
    private AgentSkillId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("agentId")
    @JoinColumn(name = "agent_id")
    private Agent agent;

    @Column(name = "skill_level", nullable = false)
    private int skillLevel; // 1=basic, 2=intermediate, 3=expert

    @Embeddable
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class AgentSkillId implements java.io.Serializable {
        @Column(name = "agent_id")
        private String agentId;

        @Column(name = "skill")
        private String skill;
    }
}
