package com.jlas.moderation.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Bản dịch bền vững của câu user nhập tự do; không phải cache TTL.
 * Table: sentence_translation
 */
@Entity
@Table(name = "sentence_translation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SentenceTranslation {

    @EmbeddedId
    private SentenceTranslationId id;

    @Column(name = "source_text", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String sourceText;

    @Column(name = "translated_text", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String translatedText;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class SentenceTranslationId implements Serializable {
        @Column(name = "source_hash", length = 64)
        private String sourceHash;

        @Column(name = "target_language", length = 10)
        private String targetLanguage;
    }
}
