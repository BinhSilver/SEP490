package com.jlas.kaiwa.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Table(name = "text_annotation_cache")
public class TextAnnotationCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_hash", nullable = false, unique = true, length = 64)
    private String contentHash;

    @Column(name = "content_ja", nullable = false, columnDefinition = "TEXT")
    private String contentJa;

    @Column(name = "furigana_json", columnDefinition = "JSON")
    private String furiganaJson;

    @Column(name = "translation_vi", columnDefinition = "TEXT")
    private String translationVi;

    @Column(nullable = false, length = 30)
    private String source = "ai";

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getContentJa() { return contentJa; }
    public void setContentJa(String contentJa) { this.contentJa = contentJa; }
    public String getFuriganaJson() { return furiganaJson; }
    public void setFuriganaJson(String furiganaJson) { this.furiganaJson = furiganaJson; }
    public String getTranslationVi() { return translationVi; }
    public void setTranslationVi(String translationVi) { this.translationVi = translationVi; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
