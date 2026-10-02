package com.jlas.learning.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * [JLAS-W0108] P6 — một chặng (stage) trong lộ trình JLPT nhiều cấp. Chặng gắn đúng một cấp mục
 * tiêu, xếp tuần tự theo {@code stageIndex}; mỗi chặng có curriculumVersion/assessmentCycle riêng
 * và trạng thái chuyển qua các giai đoạn (D5). Path một cấp (N5→N4) vẫn có đúng một chặng để giữ
 * cùng contract với path nhiều cấp (N5→N3 = chặng N4 rồi N3).
 * Table: user_learning_stage
 */
@Entity
@Table(name = "user_learning_stage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLearningStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "learning_path_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_learning_stage_path"))
    private UserLearningPath learningPath;

    /** Cấp mục tiêu của chặng (N5/N4/N3/N2/N1). */
    @Column(name = "level", nullable = false, length = 10)
    private String level;

    /** Thứ tự chặng, bắt đầu từ 0 (chặng đầu tiên). */
    @Column(name = "stage_index", nullable = false)
    private Integer stageIndex;

    /** Fingerprint bộ sách đã duyệt cho cấp này; P8 ghim manifest thật (readiness per-level). */
    @Column(name = "curriculum_version", length = 100)
    private String curriculumVersion;

    /** Đợt đánh giá cuối chặng (cycle); bắt đầu từ 1, tăng khi retest cùng chặng. */
    @Column(name = "assessment_cycle", length = 50)
    private String assessmentCycle;

    /** PENDING / ACTIVE / COMPLETED. Chỉ một chặng ACTIVE tại một thời điểm. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

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
