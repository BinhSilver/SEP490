package com.jlas.shadowing.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Table(name = "furigana_cache")
@Data
public class FuriganaCache {
    @Id
    @Column(length = 64)
    private String textHash;

    @Column(columnDefinition = "TEXT")
    private String originalText;

    @Column(columnDefinition = "TEXT")
    private String furigana;

    @Column(columnDefinition = "TEXT")
    private String translation;

    private String targetLanguage;

    private LocalDateTime cachedAt;

    @PrePersist
    protected void onCreate() {
        cachedAt = LocalDateTime.now();
    }
}
