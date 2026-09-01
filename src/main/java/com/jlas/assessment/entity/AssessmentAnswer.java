package com.jlas.assessment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Đáp án chung cho cả quiz, JLPT và Smart Test.
 * Table: assessment_answer
 */
@Entity
@Table(name = "assessment_answer")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssessmentAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attempt_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_assessment_answer_attempt"))
    private AssessmentAttempt attempt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_assessment_answer_question"))
    private AssessmentQuestion question;

    /** Chỉ số lựa chọn 1 đến 4 cho MULTIPLE_CHOICE. */
    @Column(name = "selected_option")
    private Integer selectedOption;

    /** Legacy seed compatibility. */
    @Column(name = "selected_option_id")
    private Long selectedOptionId;

    @Column(name = "answer_text", columnDefinition = "TEXT")
    private String answerText;

    /** Câu trả lời MATCHING có cấu trúc. */
    @Column(name = "answer_json", columnDefinition = "json")
    private String answerJson;

    @Column(name = "is_correct")
    private Boolean isCorrect;

    @Column(name = "answered_at")
    private LocalDateTime answeredAt;
}
