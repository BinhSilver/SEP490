package com.jlas.jlptpath.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** [JLAS-W0108] JLPT level of the path and its release state (jpath_level). */
@Entity
@Table(name = "jpath_level")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathLevel {

    @Id
    @Column(name = "level", length = 2)
    private String level;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "released", nullable = false)
    private boolean released;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "seed_version", length = 16)
    private String seedVersion;

    @Column(name = "release_note", length = 500)
    private String releaseNote;

    @Builder.Default
    @Column(name = "planned_vocab", nullable = false)
    private Integer plannedVocab = 0;

    @Builder.Default
    @Column(name = "planned_kanji", nullable = false)
    private Integer plannedKanji = 0;

    @Builder.Default
    @Column(name = "planned_grammar", nullable = false)
    private Integer plannedGrammar = 0;
}
