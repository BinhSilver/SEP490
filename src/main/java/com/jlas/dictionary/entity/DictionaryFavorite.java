package com.jlas.dictionary.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Từ yêu thích của người dùng; chỉ lưu liên kết, không sao chép nội dung từ.
 * Table: dictionary_favorite
 */
@Entity
@Table(name = "dictionary_favorite")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DictionaryFavorite {

    @EmbeddedId
    private DictionaryFavoriteId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("userId")
    @JoinColumn(name = "user_id",
            foreignKey = @ForeignKey(name = "fk_dictionary_favorite_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("vocabularyId")
    @JoinColumn(name = "vocabulary_id",
            foreignKey = @ForeignKey(name = "fk_dictionary_favorite_vocabulary"))
    private VocabularyEntry vocabularyEntry;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class DictionaryFavoriteId implements Serializable {
        @Column(name = "user_id")
        private Long userId;

        @Column(name = "vocabulary_id")
        private Long vocabularyId;
    }
}
