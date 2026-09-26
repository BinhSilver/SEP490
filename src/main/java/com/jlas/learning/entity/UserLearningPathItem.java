package com.jlas.learning.entity;

import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.VocabularyEntry;
import jakarta.persistence.*;
import lombok.*;

/**
 * Lesson trong learning path; cho phép cùng lesson xuất hiện lại để review.
 * Table: user_learning_path_item
 */
@Entity
@Table(name = "user_learning_path_item")
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lesson_id", nullable = false,
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
}
