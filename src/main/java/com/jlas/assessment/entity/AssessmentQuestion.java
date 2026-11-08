package com.jlas.assessment.entity;

import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.Outcome;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.VocabularyEntry;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Ngân hàng câu hỏi dùng chung cho Amon, JLPT mock test và Smart Test.
 * Table: assessment_question
 */
@Entity
@Table(name = "assessment_question", uniqueConstraints =
        @UniqueConstraint(name = "uk_assessment_question_source_code", columnNames = "source_code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssessmentQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** [JLAS-W0108] Stable workbook/import key; null for legacy assessment-bank rows. */
    @Column(name = "source_code", length = 100)
    private String sourceCode;

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

    /** [JLAS2-24] Mẫu ngữ pháp CHÍNH — chỉ câu GRAMMAR; dùng để gom điểm yếu theo mẫu. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "grammar_point_id",
            foreignKey = @ForeignKey(name = "fk_assessment_question_grammar"))
    private GrammarPoint grammarPoint;

    /** [JLAS2-24] Mẫu ngữ pháp PHỤ — chỉ để hiển thị, không tính điểm. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "question_grammar_point",
            joinColumns = @JoinColumn(name = "question_id",
                    foreignKey = @ForeignKey(name = "fk_qgp_question")),
            inverseJoinColumns = @JoinColumn(name = "grammar_point_id",
                    foreignKey = @ForeignKey(name = "fk_qgp_grammar")))
    private Set<GrammarPoint> grammarPoints = new LinkedHashSet<>();

    /** [JLAS-W0108] Topic câu hỏi (tag_type='TOPIC') — chỉ Reading/Listening; JLPT listening = NULL. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "topic_tag_id",
            foreignKey = @ForeignKey(name = "fk_assessment_question_topic"))
    private Tag topicTag;

    /** [JLAS-W0108] Outcome câu hỏi (READING/LISTENING) — cầu nối identity yếu. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "outcome_id",
            foreignKey = @ForeignKey(name = "fk_assessment_question_outcome"))
    private Outcome outcome;

    /** [JLAS-W0091] Từ vựng cụ thể câu KANJI/VOCABULARY hỏi tới (nullable; chỉ KANJI/VOCABULARY). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vocabulary_entry_id",
            foreignKey = @ForeignKey(name = "fk_assessment_question_vocabulary"))
    private VocabularyEntry vocabularyEntry;

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

    @JdbcTypeCode(SqlTypes.JSON)
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
