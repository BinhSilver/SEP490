package com.jlas.learning.entity;

import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.VocabularyEntry;
import jakarta.persistence.*;
import lombok.*;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;

/**
 * Lesson trong learning path; cho phép cùng lesson xuất hiện lại để review.
 * Table: user_learning_path_item
 */
@Entity
@Table(name = "user_learning_path_item", uniqueConstraints = {
        @UniqueConstraint(name = "uk_learning_path_item_order", columnNames = {"learning_path_id", "order_index"}),
        @UniqueConstraint(name = "uk_learning_path_item_package_part",
                columnNames = {"package_id", "activity_type", "part_index"})})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLearningPathItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "learning_path_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_learning_path_item_path"))
    private UserLearningPath learningPath;

    /** [JLAS-W0193] Null for legacy rows; V2 activities belong to exactly one package. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "package_id", foreignKey = @ForeignKey(name = "fk_learning_path_item_package"))
    private LearningPathPackage learningPackage;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type", length = 20)
    private LearningActivityType activityType;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_state", length = 20)
    private LearningActivityState activityState;

    /** [JLAS-W0125] Stable part identity within one package/skill; legacy activities retain part 1. */
    @Builder.Default
    @Column(name = "part_index", nullable = false)
    private Integer partIndex = 1;

    /** [JLAS-W0108] P2c — bài nguồn có thể NULL khi activity trỏ tài nguyên khác (grammar_point /
     * vocabulary_entry / reading-listening content_item) mà không bắt buộc một lesson_id giả. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lesson_id",
            foreignKey = @ForeignKey(name = "fk_learning_path_item_lesson"))
    private Lesson lesson;

    /** [JLAS-W0108] Item warm-up GRAMMAR trỏ tới mẫu ngữ pháp đã sai (nullable; NULL = cả bài). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "grammar_point_id",
            foreignKey = @ForeignKey(name = "fk_learning_path_item_grammar"))
    private GrammarPoint grammarPoint;

    /** [JLAS-W0108] Item warm-up KANJI/VOCAB trỏ tới từ vựng đã sai (nullable; NULL = cả bài). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vocabulary_entry_id",
            foreignKey = @ForeignKey(name = "fk_learning_path_item_vocab"))
    private VocabularyEntry vocabularyEntry;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    /** [JLAS-W0108] P6 — chặng mà activity thuộc về (soft-link id của {@code user_learning_stage});
     *  null cho item cũ (path một cấp trước P6) để giữ tương thích không phá lịch sử. */
    @Column(name = "stage_id")
    private Long stageId;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /** CURRICULUM hoặc REVIEW (legacy); nullable for new two-phase items. */
    @Column(name = "kind", length = 20)
    private String kind;

    /** WARM_UP hoặc GOAL_LEARNING; the item's role in the path, not the lesson kind. */
    @Column(name = "phase", length = 20)
    private String phase;

    @Column(name = "reason_code", length = 50)
    private String reasonCode;

    @Column(name = "day_number")
    private Integer dayNumber;

    @Column(name = "estimated_minutes")
    private Integer estimatedMinutes;

    /** JSON array of matched knowledge tag codes; TEXT for H2/MySQL portability. */
    @Column(name = "matched_tag_codes", columnDefinition = "TEXT")
    private String matchedTagCodes;

    /** [JLAS-W0108] P2 — tiến độ nội dung (NOT_STARTED → IN_PROGRESS → STUDIED), độc lập source lesson. */
    @Builder.Default
    @Column(name = "study_status", length = 20, nullable = false)
    private String studyStatus = "NOT_STARTED";

    /** [JLAS-W0108] P2 — tiến độ đánh giá (NOT_READY/READY → ISSUED → SUBMITTED → PASSED/NEEDS_REVIEW). */
    @Builder.Default
    @Column(name = "assessment_status", length = 20, nullable = false)
    private String assessmentStatus = "NOT_READY";
}
