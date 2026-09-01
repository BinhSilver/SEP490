package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

/**
 * Bảng nối Kanji đơn với các mục từ có chứa Kanji đó.
 * Table: vocabulary_kanji
 */
@Entity
@Table(name = "vocabulary_kanji")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VocabularyKanji {

    @EmbeddedId
    private VocabularyKanjiId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("vocabularyId")
    @JoinColumn(name = "vocabulary_id",
            foreignKey = @ForeignKey(name = "fk_vocabulary_kanji_vocabulary"))
    private VocabularyEntry vocabularyEntry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("kanjiId")
    @JoinColumn(name = "kanji_id",
            foreignKey = @ForeignKey(name = "fk_vocabulary_kanji_kanji"))
    private Kanji kanji;

    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class VocabularyKanjiId implements Serializable {
        @Column(name = "vocabulary_id")
        private Long vocabularyId;

        @Column(name = "kanji_id")
        private Long kanjiId;

        /** Vị trí ký tự, bắt đầu từ 1. */
        @Column(name = "character_position")
        private Short characterPosition;
    }
}
