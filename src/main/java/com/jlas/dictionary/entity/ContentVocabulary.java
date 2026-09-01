package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

/**
 * Bridge nội dung lesson với từ điển từ vựng.
 * Table: content_vocabulary
 */
@Entity
@Table(name = "content_vocabulary")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentVocabulary {

    @EmbeddedId
    private ContentVocabularyId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("contentItemId")
    @JoinColumn(name = "content_item_id",
            foreignKey = @ForeignKey(name = "fk_content_vocab_content"))
    private ContentItem contentItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("vocabularyId")
    @JoinColumn(name = "vocabulary_id",
            foreignKey = @ForeignKey(name = "fk_content_vocab_vocabulary"))
    private VocabularyEntry vocabularyEntry;

    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class ContentVocabularyId implements Serializable {
        @Column(name = "content_item_id")
        private Long contentItemId;

        @Column(name = "vocabulary_id")
        private Long vocabularyId;
    }
}
