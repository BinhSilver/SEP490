package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathContentStatus;
import com.jlas.jlptpath.enums.JpathSkill;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
 * [JLAS-W0108] A practice set (jpath_skill_set): one reading passage or one listening script with its questions. A listening set
 * without audioPath is never shown to learners. passageVi, scriptJson and scriptVi leave the server only after
 * every question of the set is locked.
 */
@Entity
@Table(name = "jpath_skill_set", indexes = @Index(
        name = "idx_jpath_skillset_pick", columnList = "type_code, status, sort_order"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathSkillSet {

    @Id
    @Column(name = "code", length = 24)
    private String code;

    @Column(name = "type_code", nullable = false, length = 24)
    private String typeCode;

    @Column(name = "level", nullable = false, length = 2)
    private String level;

    @Enumerated(EnumType.STRING)
    @Column(name = "skill", nullable = false, length = 10)
    private JpathSkill skill;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "passage_ja", columnDefinition = "text")
    private String passageJa;

    @Column(name = "passage_vi", columnDefinition = "text")
    private String passageVi;

    /** {@code [{speaker: M|F|N, ja}]}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "script_json", columnDefinition = "json")
    private String scriptJson;

    @Column(name = "script_vi", columnDefinition = "text")
    private String scriptVi;

    @Column(name = "image_description_vi", length = 1000)
    private String imageDescriptionVi;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_words_json", nullable = false, columnDefinition = "json")
    private String newWordsJson;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "char_count", nullable = false)
    private Integer charCount;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "question_count", nullable = false)
    private Integer questionCount;

    @Column(name = "audio_path", length = 255)
    private String audioPath;

    @Column(name = "audio_sha256", columnDefinition = "char(64)")
    private String audioSha256;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "audio_seconds")
    private Integer audioSeconds;

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
