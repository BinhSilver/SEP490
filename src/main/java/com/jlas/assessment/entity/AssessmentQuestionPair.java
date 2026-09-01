package com.jlas.assessment.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Cặp dùng cho câu MATCHING.
 * Table: assessment_question_pair
 */
@Entity
@Table(name = "assessment_question_pair")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssessmentQuestionPair {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_assessment_pair_question"))
    private AssessmentQuestion question;

    @Column(name = "left_text", nullable = false, length = 500)
    private String leftText;

    @Column(name = "right_text", nullable = false, length = 500)
    private String rightText;

    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private Integer orderIndex = 0;
}
