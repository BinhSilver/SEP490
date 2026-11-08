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
 * [JLAS-W0108] Strategy lesson of one question type (jpath_skill_lesson), 1-1 with the type. exampleJson holds no answer:
 * exampleAnswerIndex and checkAnswerIndex never leave the server before the learner passes the check question.
 */
@Entity
@Table(name = "jpath_skill_lesson")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathSkillLesson {

    @Id
    @Column(name = "type_code", length = 24)
    private String typeCode;

    @Column(name = "title_vi", nullable = false, length = 200)
    private String titleVi;

    @Column(name = "goal_vi", nullable = false, length = 500)
    private String goalVi;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "steps_json", nullable = false, columnDefinition = "json")
    private String stepsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "traps_json", nullable = false, columnDefinition = "json")
    private String trapsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tips_json", nullable = false, columnDefinition = "json")
    private String tipsJson;

    /** Passage or script, question, options, walkthrough and translation of the worked example; no answer index. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "example_json", nullable = false, columnDefinition = "json")
    private String exampleJson;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "example_answer_index", nullable = false)
    private Integer exampleAnswerIndex;

    @Column(name = "check_question_vi", nullable = false, length = 500)
    private String checkQuestionVi;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "check_options_json", nullable = false, columnDefinition = "json")
    private String checkOptionsJson;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "check_answer_index", nullable = false)
    private Integer checkAnswerIndex;

    @Column(name = "check_explanation_vi", nullable = false, length = 1000)
    private String checkExplanationVi;

    @Column(name = "example_audio_path", length = 255)
    private String exampleAudioPath;

    @Column(name = "example_audio_sha256", columnDefinition = "char(64)")
    private String exampleAudioSha256;

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
