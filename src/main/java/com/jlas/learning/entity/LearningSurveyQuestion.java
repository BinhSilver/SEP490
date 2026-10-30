package com.jlas.learning.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "learning_survey_question")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class LearningSurveyQuestion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "goal_id", nullable = false) private Long goalId;
    @Column(name = "survey_question_code", nullable = false, unique = true, length = 100) private String surveyQuestionCode;
    @Column(name = "question_order", nullable = false) private int questionOrder;
    @Column(name = "question_vi", nullable = false, columnDefinition = "TEXT") private String questionVi;
    @Column(name = "selection_type", nullable = false, length = 20) private String selectionType;
    @Column(name = "required", nullable = false) private boolean required;
    @Column(name = "active", nullable = false) private boolean active;
    @Column(name = "external_key", unique = true, length = 100) private String externalKey;
    @Column(name = "review_status", nullable = false, length = 20) private String reviewStatus;
}
