package com.jlas.auth.service;

import com.jlas.auth.dto.ProfileDashboardResponse;
import com.jlas.auth.entity.Payment;
import com.jlas.auth.entity.PremiumPlan;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.PaymentRepository;
import com.jlas.auth.repository.UserRepository;
import com.jlas.mission.entity.UserDailyProgress;
import com.jlas.mission.entity.UserLearningTargets;
import com.jlas.mission.entity.UserStreaks;
import com.jlas.mission.config.MissionClock;
import com.jlas.mission.repository.UserDailyProgressRepository;
import com.jlas.mission.repository.UserLearningTargetsRepository;
import com.jlas.mission.repository.UserStreaksRepository;
import com.jlas.mission.service.UserZoneService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ProfileDashboardService {
    private final UserRepository users;
    private final UserStreaksRepository streaks;
    private final UserLearningTargetsRepository targets;
    private final UserDailyProgressRepository dailyProgress;
    private final PaymentRepository payments;
    private final UserZoneService zones;
    private final MissionClock clock;

    @Transactional(readOnly = true)
    public ProfileDashboardResponse get(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng."));
        LocalDate today = clock.today(zones.zoneOf(userId));
        UserStreaks streak = streaks.findById(userId).orElse(null);
        UserLearningTargets target = targets.findById(userId).orElse(null);
        UserDailyProgress progress = dailyProgress.findByUserIdAndProgressDate(userId, today).orElse(null);

        var learning = new ProfileDashboardResponse.LearningSummary(
                value(streak == null ? null : streak.getCurrentStreak()),
                value(streak == null ? null : streak.getLongestStreak()),
                value(progress == null ? null : progress.getVocabularyLearned()),
                value(progress == null ? null : progress.getQuizzesCompleted()),
                value(progress == null ? null : progress.getStudyMinutes()),
                target == null ? 15 : value(target.getDailyVocabTarget()),
                target == null ? 1 : value(target.getDailyQuizTarget()),
                target == null ? 20 : value(target.getDailyStudyMinutes()));

        PremiumPlan premium = user.getPremiumPlan();
        boolean active = premium != null && user.getPremiumExpiredAt() != null
                && user.getPremiumExpiredAt().isAfter(LocalDateTime.now());
        String status = premium == null ? "FREE" : active ? "ACTIVE" : "EXPIRED";
        var plan = new ProfileDashboardResponse.PlanSummary(
                premium == null ? "Miễn phí" : premium.getName(), status, user.getPremiumExpiredAt(),
                premium == null ? null : premium.getPrice(), premium == null ? null : premium.getDurationDays());

        List<ProfileDashboardResponse.PaymentSummary> history = payments
                .findTop20ByUser_IdOrderByCreatedAtDesc(userId).stream().map(this::payment).toList();
        return new ProfileDashboardResponse(today, learning, plan, history);
    }

    private ProfileDashboardResponse.PaymentSummary payment(Payment value) {
        return new ProfileDashboardResponse.PaymentSummary(value.getId(), value.getPlanNameSnapshot(),
                value.getProvider(), value.getStatus(), value.getAmountPaid(), value.getCurrency(),
                value.getPaidAt(), value.getCreatedAt());
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }
}
