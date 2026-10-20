package com.jlas.learning.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "learning_goal")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class LearningGoal {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "goal_code", nullable = false, unique = true, length = 30) private String goalCode;
    @Column(name = "display_name_vi", nullable = false) private String displayNameVi;
    @Column(name = "description_vi", columnDefinition = "TEXT") private String descriptionVi;
    @Column(name = "entry_policy", nullable = false, length = 50) private String entryPolicy;
    @Column(name = "requires_assessment", nullable = false) private boolean requiresAssessment;
    @Column(name = "active", nullable = false) private boolean active;
    @Column(name = "external_key", unique = true, length = 100) private String externalKey;
    @Column(name = "source_code", length = 100) private String sourceCode;
    @Column(name = "review_status", nullable = false, length = 20) private String reviewStatus;
}
