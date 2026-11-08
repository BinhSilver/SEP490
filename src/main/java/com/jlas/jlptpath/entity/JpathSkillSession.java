package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.enums.JpathSkillSessionKind;
import com.jlas.jlptpath.enums.JpathSkillStep;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * [JLAS-W0108] A skill session (jpath_skill_session). openFlag is derived from completedAt (TRUE while open, NULL once completed)
 * so that uk_jpath_skill_open allows one open session per user, plan, skill and kind; it has no public setter.
 */
@Entity
@Table(name = "jpath_skill_session", uniqueConstraints = @UniqueConstraint(
        name = "uk_jpath_skill_open", columnNames = {"user_id", "plan_id", "skill", "kind", "open_flag"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathSkillSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "path_id")
    private Long pathId;

    @Column(name = "plan_id")
    private Long planId;

    @Column(name = "level", nullable = false, length = 2)
    private String level;

    @Enumerated(EnumType.STRING)
    @Column(name = "skill", nullable = false, length = 10)
    private JpathSkill skill;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 8)
    private JpathSkillSessionKind kind;

    /** NULL for SECTION sessions. */
    @Column(name = "type_code", length = 24)
    private String typeCode;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "step", nullable = false, length = 10)
    private JpathSkillStep step = JpathSkillStep.THEORY;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "set_codes_json", nullable = false, columnDefinition = "json")
    private String setCodesJson;

    @Column(name = "reused", nullable = false)
    private boolean reused;

    @Column(name = "lesson_passed_at")
    private LocalDateTime lessonPassedAt;

    @Column(name = "practice_token", columnDefinition = "char(36)")
    private String practiceToken;

    @Column(name = "dictation_token", columnDefinition = "char(36)")
    private String dictationToken;

    @Column(name = "section_token", columnDefinition = "char(36)")
    private String sectionToken;

    /** The learner's date. */
    @Column(name = "study_day", nullable = false)
    private LocalDate studyDay;

    @Column(name = "score_pct", precision = 5, scale = 2)
    private BigDecimal scorePct;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "cp_awarded", nullable = false)
    private Integer cpAwarded = 0;

    /** Server time; filled on insert when not set. */
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Setter(AccessLevel.NONE)
    @Column(name = "open_flag")
    private Boolean openFlag;

    @PrePersist
    void fillStartedAt() {
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
        syncFlag();
    }

    @PreUpdate
    void syncFlag() {
        openFlag = completedAt == null ? Boolean.TRUE : null;
    }
}
