package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

/**
 * Một nghĩa có thể có nhiều từ loại và một từ loại có thể thuộc nhiều nghĩa.
 * Table: sense_part_of_speech
 */
@Entity
@Table(name = "sense_part_of_speech")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SensePartOfSpeech {

    @EmbeddedId
    private SensePosId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("senseId")
    @JoinColumn(name = "sense_id",
            foreignKey = @ForeignKey(name = "fk_sense_pos_sense"))
    private VocabularySense sense;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("partOfSpeechId")
    @JoinColumn(name = "part_of_speech_id",
            foreignKey = @ForeignKey(name = "fk_sense_pos_pos"))
    private DictionaryPartOfSpeech partOfSpeech;

    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class SensePosId implements Serializable {
        @Column(name = "sense_id")
        private Long senseId;

        @Column(name = "part_of_speech_id")
        private Long partOfSpeechId;
    }
}
