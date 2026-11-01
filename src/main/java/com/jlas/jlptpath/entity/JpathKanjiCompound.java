package com.jlas.jlptpath.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** [JLAS-W0108] Compound word of a kanji (jpath_kanji_compound), at most 5 per kanji. */
@Entity
@Table(name = "jpath_kanji_compound")
@IdClass(JpathKanjiCompoundId.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathKanjiCompound {

    @Id
    @Column(name = "kanji_code", length = 24)
    private String kanjiCode;

    @Id
    @Column(name = "vocab_code", length = 24)
    private String vocabCode;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
