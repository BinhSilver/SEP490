package com.jlas.assessment.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Compatibility table for older seed files. New code stores options inline.
 * Table: assessment_question_option
 */
@Entity
@Table(name = "assessment_question_option")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssessmentQuestionOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_assessment_option_question"))
    private AssessmentQuestion question;

    @Column(name = "content", nullable = false, length = 255)
    private String content;

    @Column(name = "is_correct", nullable = false)
    @Builder.Default
    private Boolean isCorrect = false;

    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private Integer orderIndex = 0;
}
