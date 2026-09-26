package com.jlas.mission.service.impl;

import com.jlas.mission.config.MissionClock;
import com.jlas.mission.config.MissionProperties;
import com.jlas.mission.dto.DailyGoalProgress;
import com.jlas.mission.dto.LearningTargetsRequest;
import com.jlas.mission.entity.UserDailyProgress;
import com.jlas.mission.repository.MissionJdbcRepository;
import com.jlas.mission.repository.UserDailyProgressRepository;
import com.jlas.mission.repository.UserLearningTargetsRepository;
import com.jlas.mission.service.CreditAwardService;
import com.jlas.mission.service.DailyGoalService;
import com.jlas.mission.service.StreakService;
import com.jlas.mission.service.UserZoneService;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link DailyGoalService}. */
@Service
@RequiredArgsConstructor
public class DailyGoalServiceImpl implements DailyGoalService {

    private static final int DEFAULT_VOCAB_TARGET = 15;
    private static final int DEFAULT_QUIZ_TARGET = 1;
    private static final int DEFAULT_MINUTES_TARGET = 20;
    private static final String REF_DAILY_GOAL = "DAILY_GOAL";

    private final UserDailyProgressRepository progressRepository;
    private final UserLearningTargetsRepository targetsRepository;
    private final MissionJdbcRepository jdbc;
    private final CreditAwardService awards;
    private final StreakService streaks;
    private final UserZoneService zones;
    private final MissionClock clock;
    private final MissionProperties properties;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void addActivity(Long userId, LocalDate day, int vocabulary, int quizzes, int minutes) {
        jdbc.ensureDailyProgress(userId, day);
        UserDailyProgress progress = lockProgress(userId, day);
        progress.setVocabularyLearned(value(progress.getVocabularyLearned()) + Math.max(0, vocabulary));
        progress.setQuizzesCompleted(value(progress.getQuizzesCompleted()) + Math.max(0, quizzes));
        progress.setStudyMinutes(value(progress.getStudyMinutes()) + Math.max(0, minutes));
        evaluate(userId, progress, day);
        progressRepository.save(progress);
    }

    @Override
    @Transactional(readOnly = true)
    public DailyGoalProgress progress(Long userId, LocalDate day) {
        Targets targets = targets(userId);
        UserDailyProgress progress = progressRepository.findByUserIdAndProgressDate(userId, day).orElse(null);
        int vocabulary = progress == null ? 0 : value(progress.getVocabularyLearned());
        int quizzes = progress == null ? 0 : value(progress.getQuizzesCompleted());
        int minutes = progress == null ? 0 : value(progress.getStudyMinutes());
        boolean completed = progress != null && progress.getTargetCompletedAt() != null;
        int percent = completed ? 100 : (ratio(vocabulary, targets.vocabulary())
                + ratio(quizzes, targets.quizzes()) + ratio(minutes, targets.minutes())) / 3;
        return new DailyGoalProgress(day, vocabulary, targets.vocabulary(), quizzes, targets.quizzes(),
                minutes, targets.minutes(), percent, completed, properties.dailyGoalReward());
    }

    @Override
    @Transactional
    public DailyGoalProgress updateTargets(Long userId, LearningTargetsRequest request) {
        jdbc.saveTargets(userId, request.dailyVocabTarget(), request.dailyQuizTarget(), request.dailyStudyMinutes());
        LocalDate today = clock.today(zones.zoneOf(userId));
        progressRepository.findForUpdate(userId, today).ifPresent(progress -> {
            evaluate(userId, progress, today, new Targets(request.dailyVocabTarget(), request.dailyQuizTarget(),
                    request.dailyStudyMinutes()));
            progressRepository.save(progress);
        });
        return progress(userId, today);
    }

    private void evaluate(Long userId, UserDailyProgress progress, LocalDate day) {
        evaluate(userId, progress, day, targets(userId));
    }

    private void evaluate(Long userId, UserDailyProgress progress, LocalDate day, Targets targets) {
        if (progress.getTargetCompletedAt() != null || !meets(progress, targets)) {
            return;
        }
        progress.setTargetCompletedAt(LocalDateTime.now());
        awards.enqueue(userId, properties.dailyGoalReward(), CreditReason.DAILY_GOAL_REWARD, REF_DAILY_GOAL,
                day.toEpochDay(), "Hoàn thành mục tiêu ngày " + day, "DAILY_GOAL", "Hoàn thành mục tiêu hôm nay", null);
        streaks.recordCompletion(userId, day);
    }

    private static boolean meets(UserDailyProgress progress, Targets targets) {
        return value(progress.getVocabularyLearned()) >= targets.vocabulary()
                && value(progress.getQuizzesCompleted()) >= targets.quizzes()
                && value(progress.getStudyMinutes()) >= targets.minutes();
    }

    private Targets targets(Long userId) {
        return targetsRepository.findByUserId(userId)
                .map(value -> new Targets(value(value.getDailyVocabTarget()), value(value.getDailyQuizTarget()),
                        value(value.getDailyStudyMinutes())))
                .orElse(new Targets(DEFAULT_VOCAB_TARGET, DEFAULT_QUIZ_TARGET, DEFAULT_MINUTES_TARGET));
    }

    private UserDailyProgress lockProgress(Long userId, LocalDate day) {
        return progressRepository.findForUpdate(userId, day)
                .orElseThrow(() -> new IllegalStateException("user_daily_progress row missing after insert"));
    }

    private static int ratio(int done, int target) {
        return target <= 0 ? 100 : Math.min(100, done * 100 / target);
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }

    private record Targets(int vocabulary, int quizzes, int minutes) {
    }
}
