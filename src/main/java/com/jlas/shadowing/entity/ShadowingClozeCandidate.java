package com.jlas.shadowing.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Cloze candidate for shadowing listening practice.
 * Table: shadowing_cloze_candidate
 */
@Entity
@Table(name = "shadowing_cloze_candidate")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShadowingClozeCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_shadowing_cloze_candidate_analysis"))
    private ShadowingExerciseAnalysis analysis;

    @Column(name = "candidate_order", nullable = false)
    private Short candidateOrder;

    @Column(name = "answer_text", nullable = false, length = 500)
    private String answerText;

    @Column(name = "reading_text", length = 500)
    private String readingText;

    @Column(name = "occurrence_index", nullable = false)
    @Builder.Default
    private Short occurrenceIndex = 1;

    @Column(name = "candidate_type", nullable = false, length = 30)
    private String candidateType;

    @Column(name = "jlpt_level", length = 10)
    private String jlptLevel;

    @Column(name = "importance_factor", nullable = false)
    @Builder.Default
    private Byte importanceFactor = 0;

    @Column(name = "grammar_complexity_factor", nullable = false)
    @Builder.Default
    private Byte grammarComplexityFactor = 0;

    @Column(name = "context_predictability_factor", nullable = false)
    @Builder.Default
    private Byte contextPredictabilityFactor = 0;

    @Column(name = "acoustic_confusability_factor", nullable = false)
    @Builder.Default
    private Byte acousticConfusabilityFactor = 0;

    @Column(name = "confidence_factor", nullable = false)
    @Builder.Default
    private Byte confidenceFactor = 0;

    @Column(name = "difficulty_score", nullable = false)
    private Byte difficultyScore;

    @Column(name = "generation_source", nullable = false, length = 20)
    private String generationSource;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
