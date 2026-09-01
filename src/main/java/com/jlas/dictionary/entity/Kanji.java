package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Thông tin một chữ Kanji.
 * Table: kanji
 */
@Entity
@Table(name = "kanji")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Kanji {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Ký tự Kanji duy nhất, ví dụ 間. */
    @Column(name = "character", nullable = false, length = 8, unique = true)
    private String character;

    /** Mã Unicode, ví dụ 9593. */
    @Column(name = "unicode_code", nullable = false, length = 10, unique = true)
    private String unicodeCode;

    /** Bộ thủ chính. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "radical_id",
            foreignKey = @ForeignKey(name = "fk_kanji_radical"))
    private Radical radical;

    /** Tổng số nét của Kanji. */
    @Column(name = "stroke_count")
    private Byte strokeCount;

    /** Âm Hán Việt hiển thị, ví dụ GIAN. */
    @Column(name = "han_viet", length = 255)
    private String hanViet;

    /** Nghĩa Việt tóm tắt trên trang chi tiết Kanji. */
    @Column(name = "meaning_vi", columnDefinition = "TEXT")
    private String meaningVi;

    /** Cấp JLPT N5..N1; NULL nếu chưa xếp cấp. */
    @Column(name = "jlpt_level", length = 10)
    private String jlptLevel;

    /** URI SVG hướng dẫn thứ tự nét ở trang chi tiết. */
    @Column(name = "stroke_svg_uri", length = 500)
    private String strokeSvgUri;

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
