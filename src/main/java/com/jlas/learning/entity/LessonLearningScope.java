package com.jlas.learning.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "lesson_learning_scope")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class LessonLearningScope {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "lesson_id", nullable = false) private Long lessonId;
    @Column(name = "goal_id", nullable = false) private Long goalId;
    @Column(name = "topic_id") private Long topicId;
    @Column(name = "competency_id") private Long competencyId;
    @Column(name = "competency_level_id") private Long competencyLevelId;
    @Column(name = "learning_mode", length = 30) private String learningMode;
    @Column(name = "required", nullable = false) private boolean required;
    @Column(name = "scope_order", nullable = false) private int scopeOrder;
    @Column(name = "external_key", unique = true, length = 100) private String externalKey;
    @Column(name = "review_status", nullable = false, length = 20) private String reviewStatus;
}
