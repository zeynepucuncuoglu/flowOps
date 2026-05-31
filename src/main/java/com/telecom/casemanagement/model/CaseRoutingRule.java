package com.telecom.casemanagement.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "case_routing_rules", schema = "flowops")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CaseRoutingRule {

    @Id
    @Column(name = "case_type")
    private String caseType;

    @Column(name = "required_skill", nullable = false)
    private String requiredSkill;

    @Column(name = "min_skill_level", nullable = false)
    private int minSkillLevel;
}
