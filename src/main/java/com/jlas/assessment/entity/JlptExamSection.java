package com.jlas.assessment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Mỗi section dùng một assessment_quiz loại JLPT_SECTION làm bộ câu hỏi.
 * Table: jlpt_exam_section
 */
@Entity
@Table(name = "jlpt_exam_section")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JlptExamSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_jlpt_section_exam"))
    private JlptExam exam;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false, unique = true,
            foreignKey = @ForeignKey(name = "fk_jlpt_section_quiz"))
    private AssessmentQuiz quiz;

    /** VOCAB_GRAMMAR/READING/LISTENING/LANGUAGE_KNOWLEDGE. */
    @Column(name = "section_type", nullable = false, length = 30)
    private String sectionType;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    @Column(name = "max_score", nullable = false, precision = 6, scale = 2)
    private BigDecimal maxScore;

    @Column(name = "min_passing_score", nullable = false, precision = 6, scale = 2)
    private BigDecimal minPassingScore;

    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private Integer orderIndex = 0;
}
