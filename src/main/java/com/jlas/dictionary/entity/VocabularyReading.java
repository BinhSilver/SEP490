package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Các cách đọc KHÁC của một vocabulary_entry.
 * Table: vocabulary_reading
 */
@Entity
@Table(name = "vocabulary_reading")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VocabularyReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vocabulary_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_vocabulary_reading_entry"))
    private VocabularyEntry vocabularyEntry;

    /** Cách đọc khác của từ. */
    @Column(name = "reading", nullable = false, length = 255)
    private String reading;

    /** Cách đọc phụ đã chuẩn hóa. */
    @Column(name = "normalized_reading", nullable = false, length = 255)
    private String normalizedReading;

    /** Romaji của cách đọc phụ. */
    @Column(name = "reading_romaji", length = 255)
    private String readingRomaji;

    /** Ghi chú ngữ cảnh/cách dùng của reading. */
    @Column(name = "usage_note", length = 255)
    private String usageNote;
}
