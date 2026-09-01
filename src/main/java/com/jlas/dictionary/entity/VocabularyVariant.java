package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Cách viết biến thể của một mục từ.
 * Table: vocabulary_variant
 */
@Entity
@Table(name = "vocabulary_variant")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VocabularyVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vocabulary_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_vocabulary_variant_entry"))
    private VocabularyEntry vocabularyEntry;

    @Column(name = "variant_expression", nullable = false, length = 255)
    private String variantExpression;

    @Column(name = "normalized_expression", nullable = false, length = 255)
    private String normalizedExpression;

    /** KANJI_VARIANT/ORTHOGRAPHIC/KANA/OLD_FORM/OTHER. */
    @Column(name = "variant_type", nullable = false, length = 30)
    @Builder.Default
    private String variantType = "OTHER";
}
