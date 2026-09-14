package com.jlas.shadowing.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Word-level pronunciation result.
 * Table: pronunciation_word_result
 */
@Entity
@Table(name = "pronunciation_word_result")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PronunciationWordResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attempt_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_pronunciation_word_attempt"))
    private PronunciationAttempt attempt;

    @Column(name = "position_index", nullable = false)
    private Integer positionIndex;

    @Column(name = "word_text", nullable = false, length = 255)
    private String wordText;

    @Column(name = "accuracy_score", precision = 5, scale = 2)
    private BigDecimal accuracyScore;

    @Column(name = "error_type", length = 40)
    private String errorType;
}
