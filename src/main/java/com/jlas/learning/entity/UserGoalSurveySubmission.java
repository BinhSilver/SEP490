package com.jlas.learning.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_goal_survey_submission")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class UserGoalSurveySubmission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "goal_id", nullable = false) private Long goalId;
    @Column(name = "learning_mode", length = 30) private String learningMode;
    @Column(name = "status", nullable = false, length = 20) private String status;
    @Column(name = "version", nullable = false) private int version;
    @Column(name = "submitted_at", nullable = false) private LocalDateTime submittedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;

    @PrePersist void timestamps() {
        if (submittedAt == null) submittedAt = LocalDateTime.now();
        if (createdAt == null) createdAt = submittedAt;
    }
}
