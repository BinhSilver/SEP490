package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathEntrySource;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.enums.JpathStudyMode;
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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * [JLAS-W0108] A learner path (jpath_user_path). activeFlag is derived from status (TRUE when ACTIVE, NULL otherwise)
 * so that uk_jpath_path_active allows only one ACTIVE path per user; it has no public setter.
 */
@Entity
@Table(name = "jpath_user_path", uniqueConstraints = @UniqueConstraint(
        name = "uk_jpath_path_active", columnNames = {"user_id", "active_flag"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathUserPath {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private JpathPathStatus status = JpathPathStatus.ACTIVE;

    @Column(name = "open_level", nullable = false, length = 2)
    private String openLevel;

    @Column(name = "target_level", nullable = false, length = 2)
    private String targetLevel;

    @Column(name = "exam_date")
    private LocalDate examDate;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "daily_minutes", nullable = false)
    private Integer dailyMinutes;

    /** CSV of day codes, e.g. "WED,SAT". */
    @Column(name = "skill_days", nullable = false, length = 16)
    private String skillDays;

    @Builder.Default
    @Column(name = "rest_days", nullable = false, length = 16)
    private String restDays = "";

    @Column(name = "third_skill_day", length = 3)
    private String thirdSkillDay;

    @Enumerated(EnumType.STRING)
    @Column(name = "study_mode", nullable = false, length = 8)
    private JpathStudyMode studyMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "suggested_mode", nullable = false, length = 8)
    private JpathStudyMode suggestedMode;

    @Column(name = "suggestion_reason", nullable = false, length = 20)
    private String suggestionReason;

    @Column(name = "kana_tab", nullable = false)
    private boolean kanaTab;

    @Column(name = "kana_completed_at")
    private LocalDateTime kanaCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_source", nullable = false, length = 16)
    private JpathEntrySource entrySource;

    @Column(name = "assessment_attempt_id")
    private Long assessmentAttemptId;

    @Column(name = "smart_vocab_score", precision = 5, scale = 2)
    private BigDecimal smartVocabScore;

    @Column(name = "smart_kanji_score", precision = 5, scale = 2)
    private BigDecimal smartKanjiScore;

    @Column(name = "smart_grammar_score", precision = 5, scale = 2)
    private BigDecimal smartGrammarScore;

    @Column(name = "smart_ability_band", length = 40)
    private String smartAbilityBand;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "study_duration_months")
    private Integer studyDurationMonths;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    @Setter(AccessLevel.NONE)
    @Column(name = "active_flag")
    private Boolean activeFlag;

    @PrePersist
    @PreUpdate
    void syncFlag() {
        activeFlag = status == JpathPathStatus.ACTIVE ? Boolean.TRUE : null;
    }
}
