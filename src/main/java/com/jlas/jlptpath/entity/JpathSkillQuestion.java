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
 * [JLAS-W0108] A question of a practice set (jpath_skill_question, code {@code <setId>-Q<n>}). answerIndex never leaves the
 * server before the question is locked.
 */
@Entity
@Table(name = "jpath_skill_question")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathSkillQuestion {

    @Id
    @Column(name = "code", length = 28)
    private String code;

    @Column(name = "set_code", nullable = false, length = 24)
    private String setCode;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "question_ja", nullable = false, length = 500)
    private String questionJa;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options_json", nullable = false, columnDefinition = "json")
    private String optionsJson;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "answer_index", nullable = false)
    private Integer answerIndex;

    @Column(name = "explanation_vi", nullable = false, length = 1000)
    private String explanationVi;

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
