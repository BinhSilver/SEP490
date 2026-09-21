package com.jlas.auth.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ProfileDashboardResponse(
        LocalDate date,
        LearningSummary learning,
        PlanSummary plan,
        List<PaymentSummary> payments) {

    public record LearningSummary(int currentStreak, int longestStreak,
                                  int vocabularyLearned, int quizzesCompleted, int studyMinutes,
                                  int dailyVocabTarget, int dailyQuizTarget, int dailyStudyMinutes) { }

    public record PlanSummary(String name, String status, LocalDateTime expiresAt,
                              BigDecimal price, Integer durationDays) { }

    public record PaymentSummary(Long id, String planName, String provider, String status,
                                 BigDecimal amount, String currency, LocalDateTime paidAt,
                                 LocalDateTime createdAt) { }
}
