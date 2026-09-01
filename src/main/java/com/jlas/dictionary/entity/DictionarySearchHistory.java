package com.jlas.dictionary.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Lịch sử tra cứu dùng chung cho mọi truy vấn từ điển.
 * Table: dictionary_search_history
 */
@Entity
@Table(name = "dictionary_search_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DictionarySearchHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Có thể null nếu user chưa đăng nhập. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id",
            foreignKey = @ForeignKey(name = "fk_dictionary_history_user"))
    private User user;

    @Column(name = "query_text", nullable = false, length = 255)
    private String queryText;

    @Column(name = "normalized_query", nullable = false, length = 255)
    private String normalizedQuery;

    /** AUTO/KANJI/HIRAGANA/KATAKANA/ROMAJI/VIETNAMESE. */
    @Column(name = "search_mode", nullable = false, length = 20)
    @Builder.Default
    private String searchMode = "AUTO";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "selected_vocabulary_id",
            foreignKey = @ForeignKey(name = "fk_dictionary_history_vocabulary"))
    private VocabularyEntry selectedVocabulary;

    @Column(name = "result_count", nullable = false)
    @Builder.Default
    private Integer resultCount = 0;

    @Column(name = "searched_at", nullable = false, updatable = false)
    private LocalDateTime searchedAt;

    @PrePersist
    protected void onCreate() {
        searchedAt = LocalDateTime.now();
    }
}
