package com.jlas.learning.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * [JLAS-W0108] Câu quiz do Gemini sinh để BÙ phần ngân hàng câu thiếu, scoped theo learning path.
 * Tách bạch khỏi {@code assessment_question} (ngân hàng Smart Test) để không nhiễm nội dung AI vào
 * ngân hàng; mỗi anchor chỉ sinh đúng một câu cho một path (unique key path+anchor).
 *
 * Table: learning_generated_question
 */
@Entity
@Table(name = "learning_generated_question")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LearningGeneratedQuestion {

    public static final String ANCHOR_GRAMMAR_POINT = "GRAMMAR_POINT";
    public static final String ANCHOR_VOCABULARY_ENTRY = "VOCABULARY_ENTRY";
    public static final String ANCHOR_KNOWLEDGE_TAG = "KNOWLEDGE_TAG";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "learning_path_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_learning_generated_question_path"))
    private UserLearningPath learningPath;

    /** GRAMMAR_POINT / VOCABULARY_ENTRY / KNOWLEDGE_TAG — anchor nội dung đã ôn mà câu hỏi bám vào. */
    @Column(name = "anchor_type", nullable = false, length = 30)
    private String anchorType;

    /** Id (số, dạng chuỗi) cho GRAMMAR_POINT/VOCABULARY_ENTRY; code cho KNOWLEDGE_TAG. Soft-link, không FK. */
    @Column(name = "anchor_id", nullable = false, length = 64)
    private String anchorId;

    @Column(name = "skill", nullable = false, length = 20)
    @Builder.Default
    private String skill = "GRAMMAR";

    @Column(name = "jlpt_level", length = 10)
    private String jlptLevel;

    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;

    @Column(name = "option_1", nullable = false, length = 255)
    private String option1;

    @Column(name = "option_2", nullable = false, length = 255)
    private String option2;

    @Column(name = "option_3", nullable = false, length = 255)
    private String option3;

    @Column(name = "option_4", nullable = false, length = 255)
    private String option4;

    /** Chỉ số đáp án đúng từ 1 đến 4. */
    @Column(name = "correct_option", nullable = false)
    private Integer correctOption;

    @Column(name = "explanation", columnDefinition = "TEXT")
    private String explanation;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

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

    /** Câu hỏi sinh được phơi cho client với id ÂM để không đụng id câu ngân hàng (id dương). */
    public long negativeId() {
        return -id;
    }
}
