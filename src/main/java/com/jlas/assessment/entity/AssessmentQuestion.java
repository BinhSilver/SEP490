package com.jlas.assessment.entity;

import com.jlas.dictionary.entity.Tag;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Ngân hàng câu hỏi dùng chung cho Amon, JLPT mock test và Smart Test.
 * Table: assessment_question
 */
@Entity
@Table(name = "assessment_question")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssessmentQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Chỉ dùng khi câu hỏi gắn rule Amon. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversion_rule_id",
            foreignKey = @ForeignKey(name = "fk_assessment_question_rule"))
    private ConversionRule conversionRule;

    /** Knowledge point chính để gom weakness. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "knowledge_tag_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_assessment_question_knowledge"))
    private Tag knowledgeTag;

    /** VOCABULARY/GRAMMAR/READING/LISTENING/KANJI. */
    @Column(name = "skill", nullable = false, length = 20)
    @Builder.Default
    private String skill = "KANJI";

    /** N5/N4/N3/N2/N1. */
    @Column(name = "jlpt_level", nullable = false, length = 10)
    @Builder.Default
    private String jlptLevel = "N5";

    /** EASY/MEDIUM/HARD. */
    @Column(name = "difficulty", nullable = false, length = 20)
    @Builder.Default
    private String difficulty = "MEDIUM";

    /** MULTIPLE_CHOICE/FILL_BLANK/MATCHING. */
    @Column(name = "question_type", nullable = false, length = 30)
    @Builder.Default
    private String questionType = "MULTIPLE_CHOICE";

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "passage_html", columnDefinition = "MEDIUMTEXT")
    private String passageHtml;

    @Column(name = "audio_url", length = 500)
    private String audioUrl;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;

    /** Đáp án chuẩn cho FILL_BLANK. */
    @Column(name = "answer_text", columnDefinition = "TEXT")
    private String answerText;

    @Column(name = "option_1", length = 255)
    private String option1;

    @Column(name = "option_2", length = 255)
    private String option2;

    @Column(name = "option_3", length = 255)
    private String option3;

    @Column(name = "option_4", length = 255)
    private String option4;

    /** Chỉ số đáp án đúng từ 1 đến 4. */
    @Column(name = "correct_option")
    private Integer correctOption;

    @Column(name = "explanation", columnDefinition = "TEXT")
    private String explanation;

    @Column(name = "context_json", columnDefinition = "json")
    private String contextJson;

    /** Legacy seed compatibility. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quiz_id",
            foreignKey = @ForeignKey(name = "fk_assessment_question_legacy_quiz"))
    private AssessmentQuiz quiz;

    @Column(name = "order_index")
    private Integer orderIndex;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

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
