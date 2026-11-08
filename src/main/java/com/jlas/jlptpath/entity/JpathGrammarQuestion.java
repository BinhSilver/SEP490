package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathContentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * [JLAS-W0108] Grammar question (jpath_grammar_question). The answer fields (answerIndex, answerOrderJson, answerText,
 * acceptJson) never leave the server before the question is locked. acceptJson (FILL_TEXT) = synonyms also accepted.
 */
@Entity
@Table(name = "jpath_grammar_question")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathGrammarQuestion {

    @Id
    @Column(name = "code", length = 24)
    private String code;

    @Column(name = "grammar_code", nullable = false, length = 24)
    private String grammarCode;

    @Column(name = "level", nullable = false, length = 2)
    private String level;

    /** CHECK, ORDER, FILL or EXTRA. */
    @Column(name = "bank", nullable = false, length = 6)
    private String bank;

    /** FILL, ORDER_STAR, MEANING, ERROR, ORDER or FILL_TEXT. */
    @Column(name = "question_type", nullable = false, length = 12)
    private String questionType;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "prompt_ja", nullable = false, length = 500)
    private String promptJa;

    @Column(name = "prompt_furigana", length = 800)
    private String promptFurigana;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options_json", columnDefinition = "json")
    private String optionsJson;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "answer_index")
    private Integer answerIndex;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pieces_json", columnDefinition = "json")
    private String piecesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answer_order_json", columnDefinition = "json")
    private String answerOrderJson;

    @Column(name = "answer_text", length = 255)
    private String answerText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "accept_json", columnDefinition = "json")
    private String acceptJson;

    @Column(name = "explanation_vi", length = 1000)
    private String explanationVi;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private JpathContentStatus status = JpathContentStatus.DRAFT;

    @Column(name = "report_hidden", nullable = false)
    private boolean reportHidden;

    @Column(name = "authored", nullable = false)
    private boolean authored;

    @Column(name = "seed_version", length = 16)
    private String seedVersion;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
