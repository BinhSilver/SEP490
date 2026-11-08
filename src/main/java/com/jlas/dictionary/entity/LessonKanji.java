package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Kanji gắn tay vào bài học (CONTENT_CREATOR). Trang học viên gộp các chữ này với kanji suy ra từ
 * từ vựng của bài (content_vocabulary → vocabulary_kanji), không trùng lặp.
 * Table: lesson_kanji
 */
@Entity
@Table(name = "lesson_kanji")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LessonKanji {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lesson_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_lesson_kanji_lesson"))
    private Lesson lesson;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "kanji_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_lesson_kanji_kanji"))
    private Kanji kanji;

    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private Integer orderIndex = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
