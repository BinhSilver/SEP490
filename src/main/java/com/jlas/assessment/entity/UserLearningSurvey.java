package com.jlas.assessment.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_learning_survey")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserLearningSurvey {
    @Id @Column(name = "user_id")
    private Long userId;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @MapsId
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(nullable = false, length = 30)
    private String goal;
    @Column(name = "target_level", length = 10)
    private String targetLevel;
    @Column(name = "self_reported_level", nullable = false, length = 20)
    private String selfReportedLevel;
    @Column(name = "study_duration_months")
    private Integer studyDurationMonths;
    @Column(name = "daily_study_minutes")
    private Integer dailyStudyMinutes;
    @Column(nullable = false, length = 100)
    private String skills;
    @Column(name = "completed_at", nullable = false)
    private LocalDateTime completedAt;
}
