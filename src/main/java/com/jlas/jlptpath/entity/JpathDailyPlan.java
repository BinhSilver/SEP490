package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathDayKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * [JLAS-W0108] Plan of one study day (jpath_daily_plan); studyDay is the learner's date. skillMakeup = a study day
 * turned into a make-up skill day. cpAwarded = CP already paid by the hook for this day (badges excluded).
 */
@Entity
@Table(name = "jpath_daily_plan", uniqueConstraints = @UniqueConstraint(
        name = "uk_jpath_plan_user_day", columnNames = {"user_id", "study_day"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathDailyPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "path_id", nullable = false)
    private Long pathId;

    @Column(name = "study_day", nullable = false)
    private LocalDate studyDay;

    @Column(name = "level", nullable = false, length = 2)
    private String level;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_kind", nullable = false, length = 12)
    private JpathDayKind dayKind;

    @Column(name = "kana_phase", nullable = false)
    private boolean kanaPhase;

    @Column(name = "skill_makeup", nullable = false)
    private boolean skillMakeup;

    @Column(name = "reviews_cleared_at")
    private LocalDateTime reviewsClearedAt;

    @Column(name = "all_done_at")
    private LocalDateTime allDoneAt;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "cp_awarded", nullable = false)
    private Integer cpAwarded = 0;

    /** F237: words of the day raised after missed days (null = the chosen words per day). */
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "auto_words")
    private Integer autoWords;

    /** F237: the later finish told when even the full raise cannot keep the milestone (null = it keeps it). */
    @Column(name = "auto_finish")
    private LocalDate autoFinish;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
