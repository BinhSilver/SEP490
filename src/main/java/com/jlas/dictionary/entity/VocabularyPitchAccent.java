package com.jlas.dictionary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "vocabulary_pitch_accent")
@Getter
@Setter
public class VocabularyPitchAccent {
    @Id
    @Column(name = "vocabulary_id", nullable = false)
    private Long vocabularyId;

    @Column(name = "accents", nullable = false, length = 50)
    private String accents;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;
}
