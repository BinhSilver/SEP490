package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Bộ phận dùng để tra Kanji theo bộ thủ (dữ liệu RADKFILE). Quan hệ Kanji - bộ phận nằm ở bảng
 * nối kanji_component_map, chỉ đọc qua native query nên không cần entity riêng.
 * Table: kanji_component
 */
@Entity
@Table(name = "kanji_component")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KanjiComponent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Short id;

    /** Glyph hiển thị, ví dụ 亻. */
    @Column(name = "character", nullable = false, length = 8, unique = true)
    private String character;

    /** Ký tự RADKFILE dùng thay khi glyph không có trong JIS, ví dụ 化 cho 亻. */
    @Column(name = "source_character", nullable = false, length = 8, unique = true)
    private String sourceCharacter;

    @Column(name = "stroke_count", nullable = false)
    private Byte strokeCount;

    /** Bộ thủ Khang Hi tương ứng (để lấy tên tiếng Việt); null với bộ phận không phải bộ thủ. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "radical_id")
    private Radical radical;

    /** Thứ tự trong RADKFILE (đã theo số nét). */
    @Column(name = "display_order", nullable = false)
    private Short displayOrder;
}
