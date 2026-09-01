package com.jlas.assessment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Thuộc tính riêng của lần làm quiz Amon/lesson/unit.
 * Table: assessment_quiz_attempt
 */
@Entity
@Table(name = "assessment_quiz_attempt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssessmentQuizAttempt {

    /** Same PK as assessment_attempt. */
    @Id
    @Column(name = "attempt_id")
    private Long attemptId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "attempt_id",
            foreignKey = @ForeignKey(name = "fk_assessment_quiz_attempt_attempt"))
    private AssessmentAttempt attempt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_assessment_quiz_attempt_quiz"))
    private AssessmentQuiz quiz;

    @Column(name = "score_percent", precision = 5, scale = 2)
    private BigDecimal scorePercent;
}
