package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathSliderKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** [JLAS-W0108] History of slider changes (jpath_slider_adjustment); counted = uses one of the 2 adjustments. */
@Entity
@Table(name = "jpath_slider_adjustment")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathSliderAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "path_id", nullable = false)
    private Long pathId;

    @Column(name = "level", nullable = false, length = 2)
    private String level;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 14)
    private JpathSliderKind kind;

    @Column(name = "counted", nullable = false)
    private boolean counted;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "vocab_before")
    private Integer vocabBefore;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "vocab_after", nullable = false)
    private Integer vocabAfter;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "kanji_before")
    private Integer kanjiBefore;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "kanji_after", nullable = false)
    private Integer kanjiAfter;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "grammar_before")
    private Integer grammarBefore;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "grammar_after", nullable = false)
    private Integer grammarAfter;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "daily_minutes", nullable = false)
    private Integer dailyMinutes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
