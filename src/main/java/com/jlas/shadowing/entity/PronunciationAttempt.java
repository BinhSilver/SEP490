package com.jlas.shadowing.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Pronunciation assessment history for multi-device Shadowing practice.
 * Table: pronunciation_attempt
 */
@Entity
@Table(name = "pronunciation_attempt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PronunciationAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_pronunciation_attempt_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_pronunciation_attempt_video"))
    private Video video;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subtitle_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_pronunciation_attempt_subtitle"))
    private Subtitle subtitle;

    @Column(name = "storage_key", nullable = false, length = 700)
    private String storageKey;

    @Column(name = "audio_content_type", nullable = false, length = 100)
    private String audioContentType;

    @Column(name = "audio_size_bytes", nullable = false)
    private Long audioSizeBytes;

    @Column(name = "audio_duration_seconds", nullable = false, precision = 8, scale = 3)
    private BigDecimal audioDurationSeconds;

    @Column(name = "recognition_status", nullable = false, length = 30)
    private String recognitionStatus;

    @Column(name = "reference_text", nullable = false, columnDefinition = "TEXT")
    private String referenceText;

    @Column(name = "recognized_text", columnDefinition = "TEXT")
    private String recognizedText;

    @Column(name = "pronunciation_score", precision = 5, scale = 2)
    private BigDecimal pronunciationScore;

    @Column(name = "accuracy_score", precision = 5, scale = 2)
    private BigDecimal accuracyScore;

    @Column(name = "fluency_score", precision = 5, scale = 2)
    private BigDecimal fluencyScore;

    @Column(name = "completeness_score", precision = 5, scale = 2)
    private BigDecimal completenessScore;

    @Column(name = "prosody_score", precision = 5, scale = 2)
    private BigDecimal prosodyScore;

    @Column(name = "feedback", length = 1000)
    private String feedback;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
