package com.jlas.assessment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Kết quả theo kỹ năng của Smart Test.
 * Table: smart_test_skill_result
 */
@Entity
@Table(name = "smart_test_skill_result")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SmartTestSkillResult {

    @EmbeddedId
    private SmartTestSkillResultId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("attemptId")
    @JoinColumn(name = "attempt_id",
            foreignKey = @ForeignKey(name = "fk_smart_test_skill_session"))
    private SmartTestSession session;

    @Column(name = "estimated_level", nullable = false, length = 30)
    private String estimatedLevel;

    @Column(name = "mastery_score", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal masteryScore = BigDecimal.ZERO;

    /** LOW/MEDIUM/HIGH. */
    @Column(name = "confidence", nullable = false, length = 20)
    @Builder.Default
    private String confidence = "HIGH";

    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class SmartTestSkillResultId implements Serializable {
        @Column(name = "attempt_id")
        private Long attemptId;

        /** VOCABULARY/GRAMMAR/READING/LISTENING/KANJI. */
        @Column(name = "skill")
        private String skill;
    }
}
