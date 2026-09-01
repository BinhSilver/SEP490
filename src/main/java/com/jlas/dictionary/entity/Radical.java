package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Danh mục bộ thủ Kanji.
 * Table: radical
 */
@Entity
@Table(name = "radical")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Radical {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Short id;

    /** Số thứ tự bộ thủ chuẩn, ví dụ 169. */
    @Column(name = "radical_number", nullable = false, unique = true)
    private Short radicalNumber;

    /** Ký tự bộ thủ, ví dụ 門. */
    @Column(name = "character", nullable = false, length = 8, unique = true)
    private String character;

    /** Tên/cách gọi bộ thủ bằng tiếng Nhật. */
    @Column(name = "name_ja", length = 100)
    private String nameJa;

    /** Tên bộ thủ bằng tiếng Việt. */
    @Column(name = "name_vi", length = 100)
    private String nameVi;

    /** Nghĩa ngắn gọn của bộ thủ. */
    @Column(name = "meaning_vi", length = 255)
    private String meaningVi;

    /** Số nét của riêng bộ thủ. */
    @Column(name = "stroke_count")
    private Byte strokeCount;
}
