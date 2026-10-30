package com.jlas.kaiwa.model;

import com.jlas.kaiwa.model.SessionStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

@Entity
@Table(name = "conversation_session")
public class ConversationSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Every conversation belongs to the authenticated user who started it. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scenario_id", nullable = false)
    private Scenario scenario;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SessionStatus status = SessionStatus.IN_PROGRESS;

    @Transient
    private ConversationState currentState;

    @Column(name = "completed_objectives", columnDefinition = "JSON")
    @JdbcTypeCode(SqlTypes.JSON)
    private String completedObjectives;

    @Column(name = "current_state_code", length = 80)
    private String currentStateCode;

    @Column(name = "stage_code", length = 100)
    private String stageCode;

    @Column(name = "stage_definition_json", columnDefinition = "JSON")
    @JdbcTypeCode(SqlTypes.JSON)
    private String stageDefinitionJson;

    @Column(name = "stage_cursor", nullable = false)
    private int stageCursor;

    @Column(name = "stage_passed", nullable = false)
    private boolean stagePassed;

    @Column(name = "content_review", nullable = false)
    private boolean contentReview;

    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt;

    /**
     * Free-chat DialogueEngine state (JSON). Backend-owned phase/entity/depth/recovery.
     * Null for scenario sessions.
     */
    @Transient
    private String engineStateJson;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Transient
    private Integer durationSeconds;

    @Column(name = "overall_score", precision = 5, scale = 2)
    private java.math.BigDecimal overallScore;

    @Column(name = "score_details_json", columnDefinition = "JSON")
    @JdbcTypeCode(SqlTypes.JSON)
    private String scoreDetailsJson;

    @Column(name = "mistakes_count", nullable = false)
    private Integer mistakesCount = 0;

    @Column(columnDefinition = "TEXT")
    private String suggestions;

    @Column(name = "scored_at")
    private LocalDateTime scoredAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Scenario getScenario() { return scenario; }
    public void setScenario(Scenario scenario) { this.scenario = scenario; }
    public SessionStatus getStatus() { return status; }
    public void setStatus(SessionStatus status) { this.status = status; }
    public ConversationState getCurrentState() { return currentState; }
    public void setCurrentState(ConversationState currentState) { this.currentState = currentState; }
    public String getCompletedObjectives() { return completedObjectives; }
    public void setCompletedObjectives(String completedObjectives) { this.completedObjectives = completedObjectives; }
    public String getCurrentStateCode(){return currentStateCode;}
    public void setCurrentStateCode(String value){currentStateCode=value;}
    public String getStageCode(){return stageCode;}
    public void setStageCode(String value){stageCode=value;}
    public String getStageDefinitionJson(){return stageDefinitionJson;}
    public void setStageDefinitionJson(String value){stageDefinitionJson=value;}
    public int getStageCursor(){return stageCursor;}
    public void setStageCursor(int value){stageCursor=value;}
    public boolean isStagePassed(){return stagePassed;}
    public void setStagePassed(boolean value){stagePassed=value;}
    public boolean isContentReview(){return contentReview;}
    public void setContentReview(boolean value){contentReview=value;}
    public LocalDateTime getLastActivityAt(){return lastActivityAt;}
    public void setLastActivityAt(LocalDateTime value){lastActivityAt=value;}
    public String getEngineStateJson() { return engineStateJson; }
    public void setEngineStateJson(String engineStateJson) { this.engineStateJson = engineStateJson; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
    /** Thời lượng phiên; khi chưa tính trong request hiện tại thì suy ra từ started_at/ended_at. */
    public Integer getDurationSeconds() {
        if (durationSeconds == null && startedAt != null && endedAt != null) {
            return (int) java.time.Duration.between(startedAt, endedAt).getSeconds();
        }
        return durationSeconds;
    }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }
    public java.math.BigDecimal getOverallScore() { return overallScore; }
    public void setOverallScore(java.math.BigDecimal overallScore) { this.overallScore = overallScore; }
    public String getScoreDetailsJson() { return scoreDetailsJson; }
    public void setScoreDetailsJson(String scoreDetailsJson) { this.scoreDetailsJson = scoreDetailsJson; }
    public Integer getMistakesCount() { return mistakesCount; }
    public void setMistakesCount(Integer mistakesCount) { this.mistakesCount = mistakesCount; }
    public String getSuggestions() { return suggestions; }
    public void setSuggestions(String suggestions) { this.suggestions = suggestions; }
    public LocalDateTime getScoredAt() { return scoredAt; }
    public void setScoredAt(LocalDateTime scoredAt) { this.scoredAt = scoredAt; }
}
