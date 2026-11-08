package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathContentStatus;
import com.jlas.jlptpath.enums.JpathItemType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * [JLAS-W0108] Example sentence of a word, kanji or grammar pattern (jpath_example); role MAIN = example 1.
 * jaFurigana looks like "父親(ちちおや)は…". ownerType is VOCAB, KANJI or GRAMMAR (never KANA).
 */
@Entity
@Table(name = "jpath_example")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathExample {

    @Id
    @Column(name = "code", length = 24)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 8)
    private JpathItemType ownerType;

    @Column(name = "owner_code", nullable = false, length = 24)
    private String ownerCode;

    @Column(name = "level", nullable = false, length = 2)
    private String level;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /** MAIN or EXTRA. */
    @Column(name = "role", nullable = false, length = 5)
    private String role;

    @Column(name = "ja", nullable = false, length = 255)
    private String ja;

    @Column(name = "ja_furigana", length = 600)
    private String jaFurigana;

    @Column(name = "vi", length = 500)
    private String vi;

    @Column(name = "audio_path", length = 255)
    private String audioPath;

    @Column(name = "audio_sha256", columnDefinition = "char(64)")
    private String audioSha256;

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
