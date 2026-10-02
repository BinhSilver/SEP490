package com.jlas.assessment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Profile và kết quả cấp cao của Smart Test.
 * Table: smart_test_session
 */
@Entity
@Table(name = "smart_test_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SmartTestSession {

    @Id
    @Column(name = "attempt_id")
    private Long attemptId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "attempt_id",
            foreignKey = @ForeignKey(name = "fk_smart_test_session_attempt"))
    private AssessmentAttempt attempt;

    /** Four Smart Test goals: JLPT/BA/TRAVEL/DAILY_LIFE. */
    @Column(name = "goal", nullable = false, length = 30)
    private String goal;

    @Column(name = "target_level", length = 10)
    private String targetLevel;

    @Column(name = "self_reported_level", nullable = false, length = 20)
    private String selfReportedLevel;

    @Column(name = "study_duration_months")
    private Integer studyDurationMonths;

    @Column(name = "daily_study_minutes")
    private Integer dailyStudyMinutes;

    @Column(name = "estimated_overall_level", length = 30)
    private String estimatedOverallLevel;

    @Column(name = "overall_mastery_score", precision = 5, scale = 2)
    private BigDecimal overallMasteryScore;

    /** Persisted compatibility value: LOW/MEDIUM/HIGH. */
    @Column(name = "readiness_level", length = 20)
    private String readinessLevel;
}
