package com.jlas.jlptpath.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** [JLAS-W0108] Kana character (jpath_kana); kana compares in binary, so は/ば/ぱ are distinct. */
@Entity
@Table(name = "jpath_kana", uniqueConstraints = @UniqueConstraint(
        name = "uk_jpath_kana", columnNames = {"script", "kana"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathKana {

    @Id
    @Column(name = "code", length = 24)
    private String code;

    @Column(name = "row_code", nullable = false, length = 24)
    private String rowCode;

    /** HIRAGANA or KATAKANA. */
    @Column(name = "script", nullable = false, length = 8)
    private String script;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "kana", nullable = false, length = 4)
    private String kana;

    @Column(name = "romaji", nullable = false, length = 8)
    private String romaji;
}
