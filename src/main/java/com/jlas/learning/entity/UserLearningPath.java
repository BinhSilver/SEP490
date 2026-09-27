package com.jlas.learning.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Lộ trình học dài hạn có version của user.
 * Table: user_learning_path
 */
@Entity
@Table(name = "user_learning_path")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLearningPath {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_learning_path_user"))
    private User user;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** JLPT/TRAVEL/BA/FROM_ZERO. */
    @Column(name = "goal", length = 20)
    private String goal;

    @Column(name = "target_level", length = 10)
    private String targetLevel;

    @Column(name = "current_level", length = 10)
    private String currentLevel;

    /** Survey snapshot reference; no FK while the survey module is being built. */
    @Column(name = "survey_id")
    private Long surveyId;

    /** Assessment attempt snapshot reference; no FK to keep learning decoupled from assessment. */
    @Column(name = "assessment_attempt_id")
    private Long assessmentAttemptId;

    @Column(name = "assessment_revision", length = 50)
    private String assessmentRevision;

    @Column(name = "policy_version", length = 100)
    private String policyVersion;

    @Column(name = "minutes_per_day")
    private Integer minutesPerDay;

    /** JSON array of coverage gaps; TEXT for H2/MySQL portability. */
    @Column(name = "coverage_gaps_json", columnDefinition = "TEXT")
    private String coverageGapsJson;

    /** [JLAS-W0113] Thời điểm vượt qua gate quiz tổng kết warm-up; null = chưa vượt. */
    @Column(name = "gate_passed_at")
    private LocalDateTime gatePassedAt;

    /** [JLAS-W0108] Id bộ flashcard warm-up gộp Kanji+Từ vựng đã sai (soft-link, null = chưa sinh). */
    @Column(name = "warm_up_flashcard_set_id")
    private Long warmUpFlashcardSetId;

    /** [JLAS-W0108] Kế hoạch lộ trình cá nhân hoá do Gemini sinh (JSON: overview + phases[]);
     *  null = chưa sinh (Gemini lỗi/rate-limit), UI vẫn chạy bình thường không cần narrative. */
    @Column(name = "ai_plan_json", columnDefinition = "TEXT")
    private String aiPlanJson;

    /** ACTIVE/SUPERSEDED. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
