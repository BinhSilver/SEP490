package com.jlas.assessment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Snapshot các câu đã phát cho Smart Test.
 * Table: smart_test_session_question
 */
@Entity
@Table(name = "smart_test_session_question")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SmartTestSessionQuestion {

    @EmbeddedId
    private SmartTestSessionQuestionId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("attemptId")
    @JoinColumn(name = "attempt_id",
            foreignKey = @ForeignKey(name = "fk_smart_test_question_session"))
    private SmartTestSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("questionId")
    @JoinColumn(name = "question_id",
            foreignKey = @ForeignKey(name = "fk_smart_test_question_question"))
    private AssessmentQuestion question;

    @Column(name = "batch_number", nullable = false)
    private Integer batchNumber;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    @Column(name = "presented_at", nullable = false, updatable = false)
    private LocalDateTime presentedAt;

    @PrePersist
    protected void onCreate() {
        presentedAt = LocalDateTime.now();
    }

    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class SmartTestSessionQuestionId implements Serializable {
        @Column(name = "attempt_id")
        private Long attemptId;

        @Column(name = "question_id")
        private Long questionId;
    }
}
