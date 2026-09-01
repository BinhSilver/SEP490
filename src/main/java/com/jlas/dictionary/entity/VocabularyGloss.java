package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Diễn giải của từng nghĩa theo ngôn ngữ.
 * Table: vocabulary_gloss
 */
@Entity
@Table(name = "vocabulary_gloss")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VocabularyGloss {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sense_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_vocabulary_gloss_sense"))
    private VocabularySense sense;

    @Column(name = "language_code", nullable = false, length = 10)
    private String languageCode;

    @Column(name = "gloss_text", nullable = false, length = 2000)
    private String glossText;
}
