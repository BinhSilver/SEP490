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

    /** Optional learning presentation attached to this lesson link, not to the canonical dictionary row. */
    @Column(name = "learning_meaning_vi", columnDefinition = "TEXT")
    private String learningMeaningVi;
    @Column(name = "learning_example_ja", columnDefinition = "TEXT")
    private String learningExampleJa;
    @Column(name = "learning_example_vi", columnDefinition = "TEXT")
    private String learningExampleVi;

    /**
     * Thứ tự hiển thị từ trong bài. NULL = sắp theo id từ vựng (mặc định cũ). Migration 088 (gộp mục từ trùng) ghi
     * thứ tự cũ cho các bài bị đổi id để danh sách từ không bị đảo; từ thêm sau (NULL) xếp sau các từ đã có thứ tự.
     */
    @Column(name = "display_order")
    private Integer displayOrder;

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
