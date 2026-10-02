package com.jlas.learning.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "user_goal_survey_selection",
        uniqueConstraints = @UniqueConstraint(name = "uk_goal_survey_selection",
                columnNames = {"submission_id", "question_id", "option_id"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class UserGoalSurveySelection {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "submission_id", nullable = false) private Long submissionId;
    @Column(name = "question_id", nullable = false) private Long questionId;
    @Column(name = "option_id", nullable = false) private Long optionId;
}
