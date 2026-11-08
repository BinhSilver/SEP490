package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathMockOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * [JLAS-W0108] A JLPT mock attempt applied to the path (jpath_mock_result). The key is the mock attempt id, so one attempt is
 * applied exactly once. sectionsJson = {@code {VOCAB: {pct, passed}, ...}} after mapping (#43).
 */
@Entity
@Table(name = "jpath_mock_result")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathMockResult {

    @Id
    @Column(name = "mock_attempt_id")
    private Long mockAttemptId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "path_id", nullable = false)
    private Long pathId;

    @Column(name = "level", nullable = false, length = 2)
    private String level;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 8)
    private JpathMockOutcome outcome;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "score_pct", nullable = false)
    private Integer scorePct;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sections_json", nullable = false, columnDefinition = "json")
    private String sectionsJson;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    /** Server time; filled on insert when not set. */
    @Column(name = "applied_at", nullable = false)
    private LocalDateTime appliedAt;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "cp_awarded", nullable = false)
    private Integer cpAwarded = 0;

    @PrePersist
    void fillAppliedAt() {
        if (appliedAt == null) {
            appliedAt = LocalDateTime.now();
        }
    }
}
