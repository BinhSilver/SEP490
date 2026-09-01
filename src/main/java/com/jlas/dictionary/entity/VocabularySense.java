package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Mỗi dòng là một nghĩa/ngữ cảnh nghĩa của mục từ.
 * Table: vocabulary_sense
 */
@Entity
@Table(name = "vocabulary_sense")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VocabularySense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vocabulary_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_vocabulary_sense_entry"))
    private VocabularyEntry vocabularyEntry;

    @Column(name = "sense_order", nullable = false)
    private Short senseOrder;

    @Column(name = "usage_note", length = 1000)
    private String usageNote;

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
