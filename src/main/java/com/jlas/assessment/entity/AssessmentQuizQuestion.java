package com.jlas.assessment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Gắn câu hỏi vào một quiz/bộ đề; có thể tái sử dụng cùng câu hỏi ở nhiều nơi.
 * Table: assessment_quiz_question
 */
@Entity
@Table(name = "assessment_quiz_question")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssessmentQuizQuestion {

    @EmbeddedId
    private AssessmentQuizQuestionId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("quizId")
    @JoinColumn(name = "quiz_id",
            foreignKey = @ForeignKey(name = "fk_assessment_quiz_question_quiz"))
    private AssessmentQuiz quiz;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("questionId")
    @JoinColumn(name = "question_id",
            foreignKey = @ForeignKey(name = "fk_assessment_quiz_question_question"))
    private AssessmentQuestion question;

    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private Integer orderIndex = 0;

    @Column(name = "mondai_number")
    private Integer mondaiNumber;

    @Column(name = "raw_score", nullable = false, precision = 6, scale = 2)
    @Builder.Default
    private BigDecimal rawScore = BigDecimal.ONE;

    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class AssessmentQuizQuestionId implements Serializable {
        @Column(name = "quiz_id")
        private Long quizId;

        @Column(name = "question_id")
        private Long questionId;
    }
}
