package com.jlas.learning.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import com.jlas.learning.enums.LearningExperienceVersion;

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

    /** [JLAS-W0193] Selects the legacy or interactive contract without rewriting historical paths. */
    @Enumerated(EnumType.STRING)
    @Column(name = "experience_version", nullable = false, length = 30)
    @Builder.Default
    private LearningExperienceVersion experienceVersion = LearningExperienceVersion.LEGACY_V1;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_learning_path_user"))
    private User user;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** JLPT/TRAVEL/DAILY_LIFE/BA/FROM_ZERO. */
    @Column(name = "goal", length = 20)
    private String goal;

    /** Goal-specific delivery preference; null for legacy and JLPT paths. */
    @Column(name = "learning_mode", length = 30)
    private String learningMode;

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

    /** [JLAS-W0108] The Mock Test attempt that qualified this path; only that attempt may replay the completion. */
    @Column(name = "completion_attempt_id")
    private Long completionAttemptId;

    @Column(name = "minutes_per_day")
    private Integer minutesPerDay;

    /** JSON array of coverage gaps; TEXT for H2/MySQL portability. */
    @Column(name = "coverage_gaps_json", columnDefinition = "TEXT")
    private String coverageGapsJson;

    /** [JLAS-W0113] Thời điểm vượt qua gate quiz tổng kết warm-up; null = chưa vượt. */
    @Column(name = "gate_passed_at")
    private LocalDateTime gatePassedAt;

    /** [JLAS-W0108] Thời điểm lộ trình hoàn thành (chặng cuối đạt final); null = chưa hoàn thành.
     *  Dùng làm ngày cấp chứng chỉ thành tựu nội bộ, không suy từ updated_at. */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** [JLAS-W0108] Id bộ flashcard warm-up gộp Kanji+Từ vựng đã sai (soft-link, null = chưa sinh). */
    @Column(name = "warm_up_flashcard_set_id")
    private Long warmUpFlashcardSetId;

    /**
     * [JLAS-W0108] P2d — fingerprint membership bộ flashcard (identity expression+reading đã khử trùng,
     * sắp thứ tự). Dùng để phát hiện membership thay đổi khi replan thêm mục yếu mới: fingerprint khác
     * cột này thì {@code WarmUpFlashcardService} regenerate bộ (mục mới vào trạng thái chưa nhớ, giữ
     * tiến độ các thẻ cũ) và activity chưa được tính là hoàn thành.
     */
    @Column(name = "warm_up_flashcard_version", columnDefinition = "TEXT")
    private String warmUpFlashcardVersion;

    /** [JLAS-W0108] Kế hoạch lộ trình cá nhân hoá do Gemini sinh (JSON: overview + phases[]);
     *  null = chưa sinh (Gemini lỗi/rate-limit), UI vẫn chạy bình thường không cần narrative. */
    @Column(name = "ai_plan_json", columnDefinition = "TEXT")
    private String aiPlanJson;

    /** ACTIVE/SUPERSEDED/COMPLETED. */
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
