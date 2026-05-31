package com.telecom.casemanagement.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "agents", schema = "flowops", indexes = {
    @Index(name = "idx_agents_online", columnList = "is_online"),
    @Index(name = "idx_agents_team",   columnList = "team")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Agent {

    @Id
    @Column(name = "agent_id")
    private String agentId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String team;

    @Column(name = "is_online", nullable = false)
    private boolean online;

    @Column(name = "max_capacity", nullable = false)
    private int maxCapacity;

    @Column(name = "last_assigned_at")
    private LocalDateTime lastAssignedAt;

    @OneToMany(mappedBy = "agent", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private Set<AgentSkill> skills = new HashSet<>();

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
