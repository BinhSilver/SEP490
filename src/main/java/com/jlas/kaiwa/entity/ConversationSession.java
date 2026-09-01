package com.jlas.kaiwa.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Tóm tắt phiên hội thoại; transcript đang chạy nằm ở Redis/in-memory.
 * Table: conversation_session
 */
@Entity
@Table(name = "conversation_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_session_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scenario_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_session_scenario"))
    private Scenario scenario;

    /** IN_PROGRESS/COMPLETED/SCORED. */
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private String status = "IN_PROGRESS";

    @Column(name = "overall_score", precision = 5, scale = 2)
    private BigDecimal overallScore;

    @Column(name = "score_details_json", columnDefinition = "json")
    private String scoreDetailsJson;

    @Column(name = "mistakes_count", nullable = false)
    @Builder.Default
    private Integer mistakesCount = 0;

    @Column(name = "suggestions", columnDefinition = "TEXT")
    private String suggestions;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "scored_at")
    private LocalDateTime scoredAt;

    @PrePersist
    protected void onCreate() {
        startedAt = LocalDateTime.now();
    }
}
