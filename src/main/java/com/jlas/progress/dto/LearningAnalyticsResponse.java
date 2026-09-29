package com.jlas.progress.dto;

import java.time.LocalDate;
import java.util.List;

public record LearningAnalyticsResponse(
        LocalDate from,
        LocalDate to,
        Summary summary,
        Targets targets,
        Streak streak,
        List<Day> days) {

    public record Summary(
            int weeklyMinutes,
            int weeklyVocabulary,
            int weeklyQuizzes,
            int activeDays,
            int consistencyPercent,
            LocalDate bestDay) { }

    public record Targets(
            int dailyVocabulary,
            int dailyQuizzes,
            int dailyMinutes) { }

    public record Streak(
            int current,
            int longest) { }

    public record Day(
            LocalDate date,
            int minutes,
            int vocabulary,
            int quizzes,
            boolean targetComplete) { }
}
