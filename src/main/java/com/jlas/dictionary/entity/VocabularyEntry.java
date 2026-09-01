package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Từ hoàn chỉnh; reading là cách đọc CHÍNH và là nguồn furigana ưu tiên.
 * Table: vocabulary_entry
 */
@Entity
@Table(name = "vocabulary_entry")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VocabularyEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Dạng viết, ví dụ 時間. */
    @Column(name = "expression", nullable = false, length = 255)
    private String expression;

    /** Dạng chuẩn hóa để tìm/chống trùng. */
    @Column(name = "normalized_expression", nullable = false, length = 255)
    private String normalizedExpression;

    /** Cách đọc chính, ví dụ じかん. */
    @Column(name = "reading", nullable = false, length = 255)
    private String reading;

    /** Cách đọc đã chuẩn hóa. */
    @Column(name = "normalized_reading", nullable = false, length = 255)
    private String normalizedReading;

    /** Romaji của cách đọc chính. */
    @Column(name = "reading_romaji", length = 255)
    private String readingRomaji;

    /** Nghĩa tóm tắt cho nội dung giáo trình. */
    @Column(name = "meaning", columnDefinition = "TEXT")
    private String meaning;

    /** Từ loại tóm tắt cho giáo trình. */
    @Column(name = "word_type", length = 50)
    private String wordType;

    /** Cấp JLPT N5..N1. */
    @Column(name = "jlpt_level", length = 10)
    private String jlptLevel;

    /** Câu ví dụ tiếng Nhật. */
    @Column(name = "example_ja", columnDefinition = "TEXT")
    private String exampleJa;

    /** Bản dịch Việt của câu ví dụ. */
    @Column(name = "example_vi", columnDefinition = "TEXT")
    private String exampleVi;

    /** TEXTBOOK/DICTIONARY/BOTH. */
    @Column(name = "usage_scope", nullable = false, length = 20)
    @Builder.Default
    private String usageScope = "TEXTBOOK";

    /** DRAFT/PUBLISHED/HIDDEN. */
    @Column(name = "dictionary_status", nullable = false, length = 20)
    @Builder.Default
    private String dictionaryStatus = "PUBLISHED";

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
}
