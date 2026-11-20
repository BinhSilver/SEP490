package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathTab;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * [JLAS-W0108] An issued check set (jpath_check_set). issueJson is the public echo of the issue request; answersJson
 * never leaves the server. status: ISSUED, SUBMITTED or EXPIRED.
 */
@Entity
@Table(name = "jpath_check_set", uniqueConstraints = @UniqueConstraint(
        name = "uk_jpath_check_token", columnNames = "token"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathCheckSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "token", nullable = false, columnDefinition = "char(36)")
    private String token;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "plan_id")
    private Long planId;

    @Column(name = "session_id")
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private JpathCheckKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "tab", nullable = false, length = 8)
    private JpathTab tab;

    @Column(name = "level", length = 2)
    private String level;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "attempt_no", nullable = false)
    private Integer attemptNo = 1;

    @Builder.Default
    @Column(name = "status", nullable = false, length = 10)
    private String status = "ISSUED";

    @Column(name = "timer_on", nullable = false)
    private boolean timerOn;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "issue_json", columnDefinition = "json")
    private String issueJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "questions_json", nullable = false, columnDefinition = "json")
    private String questionsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answers_json", nullable = false, columnDefinition = "json")
    private String answersJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "progress_json", columnDefinition = "json")
    private String progressJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "item_codes_json", nullable = false, columnDefinition = "json")
    private String itemCodesJson;

    /** Server time; filled on insert when not set. */
    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "last_answer_at")
    private LocalDateTime lastAnswerAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "submit_key", length = 40)
    private String submitKey;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "correct_count")
    private Integer correctCount;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "total_count", nullable = false)
    private Integer totalCount;

    @Column(name = "score_pct", precision = 5, scale = 2)
    private BigDecimal scorePct;

    @Column(name = "passed")
    private Boolean passed;

    @Column(name = "too_fast", nullable = false)
    private boolean tooFast;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "json")
    private String resultJson;

    @PrePersist
    void fillIssuedAt() {
        if (issuedAt == null) {
            issuedAt = LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        }
    }
}
