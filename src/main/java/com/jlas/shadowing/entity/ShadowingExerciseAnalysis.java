package com.jlas.shadowing.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Cached exercise analysis for shadowing listening practice.
 * Table: shadowing_exercise_analysis
 */
@Entity
@Table(name = "shadowing_exercise_analysis")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShadowingExerciseAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subtitle_id", nullable = false, unique = true,
            foreignKey = @ForeignKey(name = "fk_shadowing_exercise_analysis_subtitle"))
    private Subtitle subtitle;

    @Column(name = "source_hash", nullable = false, length = 64)
    private String sourceHash;

    @Column(name = "prompt_version", nullable = false, length = 40)
    private String promptVersion;

    @Column(name = "vocabulary_factor", nullable = false)
    @Builder.Default
    private Byte vocabularyFactor = 0;

    @Column(name = "grammar_factor", nullable = false)
    @Builder.Default
    private Byte grammarFactor = 0;

    @Column(name = "listening_speed_factor", nullable = false)
    @Builder.Default
    private Byte listeningSpeedFactor = 0;

    @Column(name = "script_complexity_factor", nullable = false)
    @Builder.Default
    private Byte scriptComplexityFactor = 0;

    @Column(name = "listening_traps_factor", nullable = false)
    @Builder.Default
    private Byte listeningTrapsFactor = 0;

    @Column(name = "sentence_difficulty", nullable = false)
    private Byte sentenceDifficulty;

    /** AI/RULE/CACHED. */
    @Column(name = "analysis_source", nullable = false, length = 20)
    private String analysisSource;

    @Column(name = "analyzed_at", nullable = false, updatable = false)
    private LocalDateTime analyzedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        analyzedAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
