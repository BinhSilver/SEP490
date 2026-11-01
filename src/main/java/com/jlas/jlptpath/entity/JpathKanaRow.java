package com.jlas.jlptpath.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** [JLAS-W0108] Kana row (jpath_kana_row); Hiragana sort 1-18, Katakana 19-36. */
@Entity
@Table(name = "jpath_kana_row")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathKanaRow {

    @Id
    @Column(name = "code", length = 24)
    private String code;

    /** HIRAGANA or KATAKANA. */
    @Column(name = "script", nullable = false, length = 8)
    private String script;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "title_vi", nullable = false, length = 60)
    private String titleVi;

    /** BASIC, DAKUTEN, HANDAKUTEN or YOON. */
    @Column(name = "row_kind", nullable = false, length = 10)
    private String rowKind;
}
