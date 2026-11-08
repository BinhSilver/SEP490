package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathContentStatus;
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
 * [JLAS-W0108] A dictation line of a listening set (jpath_skill_dictation, code {@code <setId>-D<n>}). textJa is the answer:
 * it never leaves the server before the line is locked. blanksJson = {@code [{start, end, answer, accept:[...]}]}.
 */
@Entity
@Table(name = "jpath_skill_dictation")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathSkillDictation {

    @Id
    @Column(name = "code", length = 28)
    private String code;

    @Column(name = "set_code", nullable = false, length = 24)
    private String setCode;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "text_ja", nullable = false, length = 255)
    private String textJa;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "blanks_json", nullable = false, columnDefinition = "json")
    private String blanksJson;

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
