package com.jlas.mission.service.impl;

import com.jlas.auth.entity.User;
import com.jlas.mission.dto.DailyGoalProgress;
import com.jlas.mission.entity.UserDailyProgress;
import com.jlas.mission.entity.UserLearningTargets;
import com.jlas.mission.repository.UserDailyProgressRepository;
import com.jlas.mission.repository.UserLearningTargetsRepository;
import com.jlas.mission.service.CreditLedgerService;
import com.jlas.mission.service.DailyGoalService;
import com.jlas.mission.service.StreakService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link DailyGoalService}. */
@Service
@RequiredArgsConstructor
public class DailyGoalServiceImpl implements DailyGoalService {

    private static final int DEFAULT_VOCAB_TARGET = 15;
    private static final int DEFAULT_QUIZ_TARGET = 1;
    private static final int DEFAULT_MINUTES_TARGET = 20;
    private static final int DAILY_GOAL_REWARD_CREDITS = 10;
    private static final String REASON_DAILY_GOAL = "DAILY_GOAL_COMPLETED";
    private static final String REF_DAILY_GOAL = "DAILY_GOAL";

    private final UserDailyProgressRepository progressRepository;
    private final UserLearningTargetsRepository targetsRepository;
    private final CreditLedgerService creditLedger;
    private final StreakService streaks;
    private final EntityManager em;
    private final Clock clock;

    @Override
    @Transactional
    public void recordVocabulary(Long userId, int count) {
        UserDailyProgress progress = progressOrNew(userId);
        progress.setVocabularyLearned(value(progress.getVocabularyLearned()) + Math.max(0, count));
        progressRepository.save(progress);
        evaluate(userId, progress);
    }

    @Override
    @Transactional
    public void recordQuiz(Long userId) {
        UserDailyProgress progress = progressOrNew(userId);
        progress.setQuizzesCompleted(value(progress.getQuizzesCompleted()) + 1);
        progressRepository.save(progress);
        evaluate(userId, progress);
    }

    @Override
    @Transactional
    public void recordStudyMinutes(Long userId, int minutes) {
        UserDailyProgress progress = progressOrNew(userId);
        progress.setStudyMinutes(value(progress.getStudyMinutes()) + Math.max(0, minutes));
        progressRepository.save(progress);
        evaluate(userId, progress);
    }

    @Override
    @Transactional(readOnly = true)
    public DailyGoalProgress summary(Long userId) {
        LocalDate today = LocalDate.now(clock);
        UserLearningTargets targets = targetsOrDefaults(userId);
        UserDailyProgress progress = progressRepository.findByUserIdAndProgressDate(userId, today)
                .orElseGet(() -> zeroProgress(today, userId));
        return new DailyGoalProgress(
                value(progress.getVocabularyLearned()), value(targets.getDailyVocabTarget()),
                value(progress.getQuizzesCompleted()), value(targets.getDailyQuizTarget()),
                value(progress.getStudyMinutes()), value(targets.getDailyStudyMinutes()),
                progress.getTargetCompletedAt() != null);
    }

    private void evaluate(Long userId, UserDailyProgress progress) {
        if (progress.getTargetCompletedAt() != null) {
            return;
        }
        UserLearningTargets targets = targetsOrDefaults(userId);
        if (!meets(progress, targets)) {
            return;
        }
        progress.setTargetCompletedAt(LocalDateTime.now(clock));
        progressRepository.save(progress);

        LocalDate today = LocalDate.now(clock);
        creditLedger.award(userId, DAILY_GOAL_REWARD_CREDITS, REASON_DAILY_GOAL, REF_DAILY_GOAL, today.toEpochDay());
        streaks.recordCompletion(userId);
    }

    private boolean meets(UserDailyProgress progress, UserLearningTargets targets) {
        return value(progress.getVocabularyLearned()) >= value(targets.getDailyVocabTarget())
                && value(progress.getQuizzesCompleted()) >= value(targets.getDailyQuizTarget())
                && value(progress.getStudyMinutes()) >= value(targets.getDailyStudyMinutes());
    }

    private UserDailyProgress progressOrNew(Long userId) {
        LocalDate today = LocalDate.now(clock);
        return progressRepository.findByUserIdAndProgressDate(userId, today)
                .orElseGet(() -> UserDailyProgress.builder()
                        .user(em.getReference(User.class, userId))
                        .progressDate(today)
                        .build());
    }

    private UserLearningTargets targetsOrDefaults(Long userId) {
        return targetsRepository.findByUserId(userId)
                .orElseGet(() -> UserLearningTargets.builder()
                        .userId(userId)
                        .user(em.getReference(User.class, userId))
                        .dailyVocabTarget(DEFAULT_VOCAB_TARGET)
                        .dailyQuizTarget(DEFAULT_QUIZ_TARGET)
                        .dailyStudyMinutes(DEFAULT_MINUTES_TARGET)
                        .build());
    }

    private UserDailyProgress zeroProgress(LocalDate today, Long userId) {
        return UserDailyProgress.builder()
                .user(em.getReference(User.class, userId))
                .progressDate(today)
                .build();
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }
}
