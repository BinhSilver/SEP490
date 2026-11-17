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
import com.jlas.mission.service.DailyGoalPlanPort;
import com.jlas.mission.service.StreakService;
import com.jlas.mission.service.UserZoneService;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    private final DailyGoalPlanPort plan;

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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean refresh(Long userId, LocalDate day) {
        return complete(userId, day, targets(userId));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean refreshToday(Long userId) {
        return complete(userId, clock.today(zones.zoneOf(userId)), targets(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public DailyGoalProgress progress(Long userId, LocalDate day) {
        Targets targets = targets(userId);
        UserDailyProgress progress = progressRepository.findByUserIdAndProgressDate(userId, day).orElse(null);
        // F128: study on the JLPT / Comm path counts too; it is read from the path, never copied into the row.
        DailyGoalPlanPort.Activity path = pathActivity(userId, day);
        int vocabulary = (progress == null ? 0 : value(progress.getVocabularyLearned())) + path.vocabulary();
        int quizzes = (progress == null ? 0 : value(progress.getQuizzesCompleted())) + path.quizzes();
        int minutes = (progress == null ? 0 : value(progress.getStudyMinutes())) + path.minutes();
        boolean completed = progress != null && progress.getTargetCompletedAt() != null;
        int percent = completed ? 100 : (ratio(vocabulary, targets.vocabulary())
                + ratio(quizzes, targets.quizzes()) + ratio(minutes, targets.minutes())) / 3;
        return new DailyGoalProgress(day, vocabulary, targets.vocabulary(), quizzes, targets.quizzes(),
                minutes, targets.minutes(), percent, completed, properties.dailyGoalReward(),
                targets.source(), targets.fallbackReason());
    }

    @Override
    @Transactional
    public DailyGoalProgress updateTargets(Long userId, LearningTargetsRequest request) {
        jdbc.saveTargets(userId, request.dailyVocabTarget(), request.dailyQuizTarget(), request.dailyStudyMinutes());
        LocalDate today = clock.today(zones.zoneOf(userId));
        complete(userId, today, new Targets(request.dailyVocabTarget(), request.dailyQuizTarget(),
                request.dailyStudyMinutes(), "USER_TARGETS", null));
        return progress(userId, today);
    }

    /**
     * Completes {@code day} when its stored counters plus the path activity meet {@code targets}. The progress row is
     * created and locked only then, so a check that changes nothing writes nothing.
     */
    private boolean complete(Long userId, LocalDate day, Targets targets) {
        DailyGoalPlanPort.Activity path = pathActivity(userId, day);
        UserDailyProgress seen = progressRepository.findByUserIdAndProgressDate(userId, day).orElse(null);
        if (seen != null && seen.getTargetCompletedAt() != null) {
            return false;
        }
        if (!meets(seen, path, targets)) {
            return false;
        }
        jdbc.ensureDailyProgress(userId, day);
        UserDailyProgress progress = lockProgress(userId, day);
        boolean completed = evaluate(userId, progress, day, targets, path);
        if (completed) {
            progressRepository.save(progress);
        }
        return completed;
    }

    private void evaluate(Long userId, UserDailyProgress progress, LocalDate day) {
        evaluate(userId, progress, day, targets(userId), pathActivity(userId, day));
    }

    private boolean evaluate(Long userId, UserDailyProgress progress, LocalDate day, Targets targets,
                             DailyGoalPlanPort.Activity path) {
        if (progress.getTargetCompletedAt() != null || !meets(progress, path, targets)) {
            return false;
        }
        progress.setTargetCompletedAt(LocalDateTime.now());
        awards.enqueue(userId, properties.dailyGoalReward(), CreditReason.DAILY_GOAL_REWARD, REF_DAILY_GOAL,
                day.toEpochDay(), "Hoàn thành mục tiêu ngày " + day, "DAILY_GOAL", "Hoàn thành mục tiêu hôm nay", null);
        streaks.recordCompletion(userId, day);
        return true;
    }

    private static boolean meets(UserDailyProgress progress, DailyGoalPlanPort.Activity path, Targets targets) {
        int vocabulary = progress == null ? 0 : value(progress.getVocabularyLearned());
        int quizzes = progress == null ? 0 : value(progress.getQuizzesCompleted());
        int minutes = progress == null ? 0 : value(progress.getStudyMinutes());
        return vocabulary + path.vocabulary() >= targets.vocabulary()
                && quizzes + path.quizzes() >= targets.quizzes()
                && minutes + path.minutes() >= targets.minutes();
    }

    /** [JLAS-W0108] F128: the learner's study on their paths that day; never null. */
    private DailyGoalPlanPort.Activity pathActivity(Long userId, LocalDate day) {
        ZoneId zone = zones.zoneOf(userId);
        DailyGoalPlanPort.Activity activity = plan.activity(userId, day, zone);
        return activity == null ? DailyGoalPlanPort.Activity.NONE : activity;
    }

    private Targets targets(Long userId) {
        var aligned = plan.resolve(userId);
        if (aligned.isPresent()) {
            var value = aligned.get();
            return new Targets(value.vocabularyTarget(), value.quizTarget(), value.minutesTarget(),
                    value.source(), null);
        }
        return targetsRepository.findByUserId(userId)
                .map(value -> new Targets(value(value.getDailyVocabTarget()), value(value.getDailyQuizTarget()),
                        value(value.getDailyStudyMinutes()), "USER_TARGETS", "NO_ACTIONABLE_PATH_PACKAGE"))
                .orElse(new Targets(DEFAULT_VOCAB_TARGET, DEFAULT_QUIZ_TARGET, DEFAULT_MINUTES_TARGET,
                        "DEFAULTS", "NO_ACTIVE_PATH_OR_USER_TARGETS"));
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

    private record Targets(int vocabulary, int quizzes, int minutes, String source, String fallbackReason) {
    }
}
