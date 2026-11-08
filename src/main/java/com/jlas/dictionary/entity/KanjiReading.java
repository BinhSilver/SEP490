package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Danh sách cách đọc chuẩn hóa của từng Kanji.
 * Table: kanji_reading
 */
@Entity
@Table(name = "kanji_reading")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KanjiReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Kanji sở hữu cách đọc. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "kanji_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_kanji_reading_kanji"))
    private Kanji kanji;

    /** ON/KUN/NANORI/OTHER. */
    @Column(name = "reading_type", nullable = false, length = 20)
    private String readingType;

    /** Nội dung cách đọc, ví dụ カン hoặc あいだ. */
    @Column(name = "reading_text", nullable = false, length = 100)
    private String readingText;

    /** 1 nếu là cách đọc nên học trước — suy từ thứ tự trong KANJIDIC2, không phải thống kê tần suất. */
    @Column(name = "is_common", nullable = false)
    @Builder.Default
    private Boolean isCommon = false;
}
