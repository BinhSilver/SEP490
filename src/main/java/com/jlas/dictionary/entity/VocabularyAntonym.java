package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "vocabulary_antonym")
@Getter
@Setter
public class VocabularyAntonym {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vocabulary_id", nullable = false)
    private Long vocabularyId;

    @Column(name = "antonym_text", nullable = false, length = 255)
    private String antonymText;

    @Column(name = "antonym_reading", length = 255)
    private String antonymReading;

    @Column(name = "antonym_sense")
    private Integer antonymSense;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;
}
