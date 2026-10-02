package com.jlas.learning.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "learning_survey_option")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class LearningSurveyOption {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "question_id", nullable = false) private Long questionId;
    @Column(name = "survey_option_code", nullable = false, unique = true, length = 100) private String surveyOptionCode;
    @Column(name = "option_order", nullable = false) private int optionOrder;
    @Column(name = "label_vi", nullable = false) private String labelVi;
    @Column(name = "value_code", nullable = false, length = 100) private String valueCode;
    @Column(name = "topic_id") private Long topicId;
    @Column(name = "competency_id") private Long competencyId;
    @Column(name = "learning_mode", length = 30) private String learningMode;
    @Column(name = "external_key", unique = true, length = 100) private String externalKey;
    @Column(name = "review_status", nullable = false, length = 20) private String reviewStatus;
}
