package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathContentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/** [JLAS-W0108] Path kanji (jpath_kanji). `character` is a reserved word and is always quoted. */
@Entity
@Table(name = "jpath_kanji", uniqueConstraints = @UniqueConstraint(
        name = "uk_jpath_kanji_char", columnNames = {"`character`"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathKanji {

    @Id
    @Column(name = "code", length = 24)
    private String code;

    @Column(name = "level", nullable = false, length = 2)
    private String level;

    @Column(name = "`character`", nullable = false, length = 4)
    private String character;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "han_viet", length = 40)
    private String hanViet;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "han_viet_alt_json", columnDefinition = "json")
    private String hanVietAltJson;

    @Column(name = "meanings_vi", length = 255)
    private String meaningsVi;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "readings_on_json", columnDefinition = "json")
    private String readingsOnJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "readings_kun_json", columnDefinition = "json")
    private String readingsKunJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "level_readings_json", columnDefinition = "json")
    private String levelReadingsJson;

    /** Where level_readings came from; FIRST_READING/OVERRIDE readings are shown but never asked in checks. */
    @Column(name = "level_reading_source", length = 20)
    private String levelReadingSource;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "stroke_count")
    private Integer strokeCount;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "radical_number")
    private Integer radicalNumber;

    @Column(name = "radical_name_vi", length = 40)
    private String radicalNameVi;

    @Column(name = "frequency")
    private Integer frequency;

    @Column(name = "mnemonic_vi", length = 500)
    private String mnemonicVi;

    @Column(name = "dictionary_kanji_id")
    private Long dictionaryKanjiId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private JpathContentStatus status = JpathContentStatus.DRAFT;

    @Column(name = "report_hidden", nullable = false)
    private boolean reportHidden;

    @Column(name = "authored", nullable = false)
    private boolean authored;

    @Column(name = "seed_version", length = 16)
    private String seedVersion;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
