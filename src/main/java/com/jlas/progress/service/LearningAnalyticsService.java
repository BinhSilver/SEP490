package com.jlas.progress.service;

import com.jlas.mission.entity.UserDailyProgress;
import com.jlas.mission.entity.UserLearningTargets;
import com.jlas.mission.entity.UserStreaks;
import com.jlas.mission.repository.UserDailyProgressRepository;
import com.jlas.mission.repository.UserLearningTargetsRepository;
import com.jlas.mission.repository.UserStreaksRepository;
import com.jlas.progress.dto.LearningAnalyticsResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LearningAnalyticsService {
    private static final int WINDOW_DAYS = 7;
    private static final int DEFAULT_DAILY_VOCABULARY = 15;
    private static final int DEFAULT_DAILY_QUIZZES = 1;
    private static final int DEFAULT_DAILY_MINUTES = 20;

    private final UserDailyProgressRepository dailyProgress;
    private final UserLearningTargetsRepository learningTargets;
    private final UserStreaksRepository streaks;

    @Value("${retention.zone-id:Asia/Ho_Chi_Minh}")
    private String zoneId;

    @Transactional(readOnly = true)
    public LearningAnalyticsResponse get(Long userId) {
        LocalDate to = LocalDate.now(ZoneId.of(zoneId));
        LocalDate from = to.minusDays(WINDOW_DAYS - 1L);
        Map<LocalDate, UserDailyProgress> byDate = new HashMap<>();
        dailyProgress.findByUser_IdAndProgressDateBetweenOrderByProgressDateAsc(userId, from, to)
                .forEach(value -> byDate.put(value.getProgressDate(), value));

        UserLearningTargets target = learningTargets.findById(userId).orElse(null);
        int dailyVocabulary = target == null ? DEFAULT_DAILY_VOCABULARY : value(target.getDailyVocabTarget(), DEFAULT_DAILY_VOCABULARY);
        int dailyQuizzes = target == null ? DEFAULT_DAILY_QUIZZES : value(target.getDailyQuizTarget(), DEFAULT_DAILY_QUIZZES);
        int dailyMinutes = target == null ? DEFAULT_DAILY_MINUTES : value(target.getDailyStudyMinutes(), DEFAULT_DAILY_MINUTES);

        List<LearningAnalyticsResponse.Day> days = from.datesUntil(to.plusDays(1))
                .map(date -> day(date, byDate.get(date), dailyVocabulary, dailyQuizzes, dailyMinutes))
                .toList();
        int weeklyMinutes = days.stream().mapToInt(LearningAnalyticsResponse.Day::minutes).sum();
        int weeklyVocabulary = days.stream().mapToInt(LearningAnalyticsResponse.Day::vocabulary).sum();
        int weeklyQuizzes = days.stream().mapToInt(LearningAnalyticsResponse.Day::quizzes).sum();
        int activeDays = (int) days.stream().filter(this::isActive).count();
        int consistency = Math.round(activeDays * 100f / WINDOW_DAYS);
        LocalDate bestDay = days.stream()
                .max((left, right) -> Integer.compare(score(left), score(right)))
                .filter(this::isActive)
                .map(LearningAnalyticsResponse.Day::date)
                .orElse(null);

        UserStreaks streak = streaks.findById(userId).orElse(null);
        return new LearningAnalyticsResponse(
                from,
                to,
                new LearningAnalyticsResponse.Summary(weeklyMinutes, weeklyVocabulary, weeklyQuizzes,
                        activeDays, consistency, bestDay),
                new LearningAnalyticsResponse.Targets(dailyVocabulary, dailyQuizzes, dailyMinutes),
                new LearningAnalyticsResponse.Streak(
                        streak == null ? 0 : value(streak.getCurrentStreak(), 0),
                        streak == null ? 0 : value(streak.getLongestStreak(), 0)),
                days);
    }

    private static LearningAnalyticsResponse.Day day(LocalDate date, UserDailyProgress progress,
                                                       int dailyVocabulary, int dailyQuizzes, int dailyMinutes) {
        int vocabulary = progress == null ? 0 : value(progress.getVocabularyLearned(), 0);
        int quizzes = progress == null ? 0 : value(progress.getQuizzesCompleted(), 0);
        int minutes = progress == null ? 0 : value(progress.getStudyMinutes(), 0);
        boolean complete = progress != null && progress.getTargetCompletedAt() != null;
        if (!complete) {
            complete = vocabulary >= dailyVocabulary && quizzes >= dailyQuizzes && minutes >= dailyMinutes;
        }
        return new LearningAnalyticsResponse.Day(date, minutes, vocabulary, quizzes, complete);
    }

    private boolean isActive(LearningAnalyticsResponse.Day day) {
        return day.minutes() > 0 || day.vocabulary() > 0 || day.quizzes() > 0;
    }

    private static int score(LearningAnalyticsResponse.Day day) {
        return day.minutes() + day.vocabulary() * 2 + day.quizzes() * 10;
    }

    private static int value(Integer value, int fallback) {
        return value == null ? fallback : Math.max(0, value);
    }
}
