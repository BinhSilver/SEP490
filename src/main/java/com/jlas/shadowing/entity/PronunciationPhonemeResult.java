package com.jlas.shadowing.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Phoneme-level pronunciation result.
 * Table: pronunciation_phoneme_result
 */
@Entity
@Table(name = "pronunciation_phoneme_result")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PronunciationPhonemeResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "word_result_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_pronunciation_phoneme_word"))
    private PronunciationWordResult wordResult;

    @Column(name = "position_index", nullable = false)
    private Integer positionIndex;

    @Column(name = "phoneme_text", nullable = false, length = 100)
    private String phonemeText;

    @Column(name = "accuracy_score", precision = 5, scale = 2)
    private BigDecimal accuracyScore;
}
