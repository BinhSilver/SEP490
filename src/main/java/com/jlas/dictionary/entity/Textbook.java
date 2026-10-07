package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Đầu sách/giáo trình.
 * Table: textbook
 */
@Entity
@Table(name = "textbook")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Textbook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "jlpt_level", length = 10)
    private String jlptLevel;

    /** Ảnh thumbnail tải lên (/uploads/textbook-thumbnails/...); null thì frontend vẽ thumbnail tự sinh. */
    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    /** Chữ Hán hiện mờ trên thumbnail tự sinh; null = 日. */
    @Column(name = "thumbnail_kanji", length = 4)
    private String thumbnailKanji;

    /** Màu nền thumbnail tự sinh dạng #RRGGBB; null = chọn theo hash tên giáo trình. */
    @Column(name = "theme_color", length = 7)
    private String themeColor;

    /** DRAFT/PUBLISHED/ARCHIVED. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "DRAFT";

    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
