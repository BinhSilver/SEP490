package com.jlas.assessment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Điểm từng section. is_passed_section được tính từ score và min_passing_score (view).
 * Table: jlpt_section_result
 */
@Entity
@Table(name = "jlpt_section_result")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JlptSectionResult {

    @EmbeddedId
    private JlptSectionResultId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("attemptId")
    @JoinColumn(name = "attempt_id",
            foreignKey = @ForeignKey(name = "fk_jlpt_section_result_attempt"))
    private JlptExamAttempt examAttempt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("sectionId")
    @JoinColumn(name = "section_id",
            foreignKey = @ForeignKey(name = "fk_jlpt_section_result_section"))
    private JlptExamSection section;

    @Column(name = "score", nullable = false, precision = 6, scale = 2)
    @Builder.Default
    private BigDecimal score = BigDecimal.ZERO;

    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class JlptSectionResultId implements Serializable {
        @Column(name = "attempt_id")
        private Long attemptId;

        @Column(name = "section_id")
        private Long sectionId;
    }
}
