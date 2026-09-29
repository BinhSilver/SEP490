package com.jlas.mission.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.mission.config.MissionClock;
import com.jlas.mission.config.MissionProperties;
import com.jlas.mission.dto.FreezeActionResponse;
import com.jlas.mission.dto.FreezeOfferResponse;
import com.jlas.mission.dto.StreakDayResponse;
import com.jlas.mission.dto.StreakResponse;
import com.jlas.mission.entity.MissionDefinition;
import com.jlas.mission.entity.UserDailyProgress;
import com.jlas.mission.entity.UserStreaks;
import com.jlas.mission.enums.FreezeDayStatus;
import com.jlas.mission.repository.MissionJdbcRepository;
import com.jlas.mission.repository.RewardCelebrationRepository;
import com.jlas.mission.repository.StreakFreezeDayRepository;
import com.jlas.mission.repository.StreakFreezeDayRepository.FreezeDay;
import com.jlas.mission.repository.UserDailyProgressRepository;
import com.jlas.mission.repository.UserStreaksRepository;
import com.jlas.mission.service.CreditAwardService;
import com.jlas.mission.service.DailyMissionService;
import com.jlas.mission.service.StreakService;
import com.jlas.mission.service.UserZoneService;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import com.jlas.moderation.credit.service.UserCreditService;
import com.jlas.notification.service.UserNotificationPort;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link StreakService}. */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreakServiceImpl implements StreakService {

    private static final String REF_FREEZE_DAY = "STREAK_FREEZE_DAY";
    private static final String REF_MILESTONE = "STREAK_MILESTONE";
    private static final String SOURCE_INVENTORY = "INVENTORY";
    private static final String SOURCE_PURCHASE = "PURCHASE";

    private final UserStreaksRepository streaks;
    private final StreakFreezeDayRepository freezeDays;
    private final UserDailyProgressRepository dailyProgress;
    private final MissionJdbcRepository jdbc;
    private final RewardCelebrationRepository celebrations;
    private final DailyMissionService missions;
    private final CreditAwardService awards;
    private final UserCreditService credits;
    private final UserNotificationPort notifications;
    private final UserZoneService zones;
    private final MissionClock clock;
    private final MissionProperties properties;

    /** Self reference so each learner checked by the reminder job gets its own transaction. */
    @Lazy
    @Autowired
    private StreakService self;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCompletion(Long userId, LocalDate day) {
        jdbc.ensureStreak(userId);
        UserStreaks streak = lock(userId);
        LocalDate last = streak.getLastCompletedDate();
        if (last != null && !day.isAfter(last)) {
            return;
        }
        int current = value(streak.getCurrentStreak());
        boolean continues = current > 0 && last != null && gapFullyFrozen(userId, last, day);
        if (!continues && current > 0 && last != null && last.plusDays(2).equals(day)) {
            // Exactly one missed day and it is still protectable today: offer the freeze before restarting.
            LocalDate missed = day.minusDays(1);
            if (freezeDays.offer(userId, missed, current)) {
                notifyAtRisk(userId, streak, missed, current);
            }
        }
        int next = continues ? current + 1 : 1;
        streak.setCurrentStreak(next);
        streak.setLongestStreak(Math.max(value(streak.getLongestStreak()), next));
        streak.setLastCompletedDate(day);
        // An offer for the day before {@code day} stays open: the learner may still protect it today.
        freezeDays.expireOpenOffersBefore(userId, day.minusDays(1));
        celebrations.add(userId, "streak:" + day, "STREAK_EXTENDED",
                next == 1 ? "Bắt đầu chuỗi mới!" : "Chuỗi " + next + " ngày!", 0, next);
        grantMilestones(userId, streak, next);
        streaks.save(streak);
    }

    @Override
    @Transactional
    public StreakResponse refresh(Long userId) {
        ZoneId zone = zones.zoneOf(userId);
        LocalDate today = clock.today(zone);
        Optional<UserStreaks> locked = streaks.findForUpdate(userId);
        if (locked.isEmpty()) {
            return view(userId, null, null, today, zone);
        }
        UserStreaks streak = locked.get();
        FreezeDay offer = evaluate(userId, streak, today);
        return view(userId, streak, offer, today, zone);
    }

    @Override
    @Transactional
    public FreezeActionResponse useFreeze(Long userId, boolean purchase) {
        ZoneId zone = zones.zoneOf(userId);
        LocalDate today = clock.today(zone);
        UserStreaks streak = streaks.findForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "Bạn chưa có chuỗi ngày nào để bảo vệ."));
        FreezeDay offer = evaluate(userId, streak, today);
        LocalDate missed = today.minusDays(1);
        FreezeDay day = freezeDays.lock(userId, offer == null ? missed : offer.date()).orElse(null);
        if (day != null && day.status() == FreezeDayStatus.USED) {
            // Repeated click or retry: the day is already protected, charge nothing.
            return new FreezeActionResponse(view(userId, streak, null, today, zone), credits.balance(userId), 0);
        }
        if (day == null || day.status() != FreezeDayStatus.OFFERED) {
            throw new ApiException(ErrorCode.CONFLICT, "Không có ngày nào cần bảo vệ lúc này.");
        }

        int spent = 0;
        String source;
        if (purchase) {
            credits.spend(userId, properties.freezePrice(), CreditReason.STREAK_FREEZE, REF_FREEZE_DAY,
                    day.date().toEpochDay(), "Mua thẻ bảo vệ giữ chuỗi");
            spent = properties.freezePrice();
            source = SOURCE_PURCHASE;
        } else {
            if (value(streak.getFreezeCount()) <= 0) {
                throw new ApiException(ErrorCode.CONFLICT, "Bạn không còn thẻ bảo vệ. Hãy mua bằng CP.");
            }
            streak.setFreezeCount(value(streak.getFreezeCount()) - 1);
            source = SOURCE_INVENTORY;
        }
        freezeDays.decide(userId, day.date(), FreezeDayStatus.USED, source);
        streak.setLastFreezeUsedDate(day.date());

        // Completing today before deciding restarted the count at 1; the freeze joins both halves.
        boolean completedAfterMiss = day.date().plusDays(1).equals(streak.getLastCompletedDate());
        int restored = day.streakAtRisk() + (completedAfterMiss ? 1 : 0);
        streak.setCurrentStreak(Math.max(value(streak.getCurrentStreak()), restored));
        streak.setLongestStreak(Math.max(value(streak.getLongestStreak()), value(streak.getCurrentStreak())));
        grantMilestones(userId, streak, value(streak.getCurrentStreak()));
        streaks.save(streak);
        return new FreezeActionResponse(view(userId, streak, null, today, zone), credits.balance(userId), spent);
    }

    @Override
    @Transactional
    public FreezeActionResponse declineFreeze(Long userId) {
        ZoneId zone = zones.zoneOf(userId);
        LocalDate today = clock.today(zone);
        UserStreaks streak = streaks.findForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "Bạn chưa có chuỗi ngày nào."));
        FreezeDay offer = evaluate(userId, streak, today);
        if (offer != null) {
            freezeDays.decide(userId, offer.date(), FreezeDayStatus.DECLINED, null);
            if (!today.equals(streak.getLastCompletedDate())) {
                streak.setCurrentStreak(0);
            }
            streaks.save(streak);
        }
        return new FreezeActionResponse(view(userId, streak, null, today, zone), credits.balance(userId), 0);
    }

    @Override
    public int remindAtRiskStreaks(int limit) {
        // Broad pre-filter in UTC; each learner is then checked precisely in their own zone.
        LocalDate utcToday = LocalDate.ofInstant(clock.now(), ZoneOffset.UTC);
        List<Long> userIds = jdbc.findStreakUsersLastCompletedBefore(utcToday, limit);
        for (Long userId : userIds) {
            try {
                self.refresh(userId);
            } catch (RuntimeException ex) {
                log.warn("Streak check failed for user {}: {}", userId, ex.getMessage());
            }
        }
        return userIds.size();
    }

    /**
     * Walks the days missed since the last completion. Frozen days are skipped; yesterday gets an
     * offer (and a notification the first time); an older unprotected day ends the streak.
     */
    private FreezeDay evaluate(Long userId, UserStreaks streak, LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        freezeDays.expireOpenOffersBefore(userId, yesterday);
        int current = value(streak.getCurrentStreak());
        LocalDate last = streak.getLastCompletedDate();
        if (current == 0 || last == null) {
            return null;
        }
        if (!last.isBefore(yesterday)) {
            return last.equals(today) ? openOffer(userId, yesterday) : null;
        }
        Set<LocalDate> frozen = freezeDays.findUsedBetween(userId, last.plusDays(1), yesterday);
        for (LocalDate day = last.plusDays(1); !day.isAfter(yesterday); day = day.plusDays(1)) {
            if (frozen.contains(day)) {
                continue;
            }
            if (day.equals(yesterday)) {
                if (freezeDays.offer(userId, day, current)) {
                    notifyAtRisk(userId, streak, day, current);
                }
                FreezeDay offer = openOffer(userId, day);
                if (offer != null) {
                    return offer;
                }
            }
            streak.setCurrentStreak(0);
            streaks.save(streak);
            return null;
        }
        return null;
    }

    private FreezeDay openOffer(Long userId, LocalDate day) {
        return freezeDays.find(userId, day).filter(value -> value.status() == FreezeDayStatus.OFFERED).orElse(null);
    }

    private boolean gapFullyFrozen(Long userId, LocalDate last, LocalDate day) {
        long missed = ChronoUnit.DAYS.between(last, day) - 1;
        if (missed <= 0) {
            return true;
        }
        return freezeDays.findUsedBetween(userId, last.plusDays(1), day.minusDays(1)).size() == missed;
    }

    /** Milestones pay once per lifetime; long ones also add a freeze if the learner has room. */
    private void grantMilestones(Long userId, UserStreaks streak, int current) {
        for (MissionDefinition milestone : missions.activeStreakMilestones()) {
            int days = value(milestone.getTargetCount());
            if (days <= 0 || current < days || !missions.completeLifetime(userId, milestone)) {
                continue;
            }
            awards.enqueue(userId, value(milestone.getRewardCredits()), CreditReason.STREAK_MILESTONE_REWARD,
                    REF_MILESTONE, milestone.getId(), milestone.getTitle(),
                    "STREAK_MILESTONE", milestone.getTitle(), days);
            if (days >= properties.freezeBonusFromMilestone()
                    && value(streak.getFreezeCount()) < properties.maxFreezes()) {
                streak.setFreezeCount(value(streak.getFreezeCount()) + 1);
            }
        }
    }

    private void notifyAtRisk(Long userId, UserStreaks streak, LocalDate missed, int current) {
        boolean owned = value(streak.getFreezeCount()) > 0;
        String message = owned
                ? "Hôm qua bạn chưa đạt mục tiêu. Dùng 1 thẻ bảo vệ để giữ chuỗi " + current
                        + " ngày, trước khi hết hôm nay."
                : "Hôm qua bạn chưa đạt mục tiêu. Mua thẻ bảo vệ với " + properties.freezePrice()
                        + " CP để giữ chuỗi " + current + " ngày, trước khi hết hôm nay.";
        notifications.notifyUser(userId, "STREAK", "Chuỗi " + current + " ngày sắp mất", message,
                "/goals?freeze=" + missed, "streak-freeze:" + missed);
    }

    private StreakResponse view(Long userId, UserStreaks streak, FreezeDay offer, LocalDate today, ZoneId zone) {
        int current = streak == null ? 0 : value(streak.getCurrentStreak());
        int longest = streak == null ? 0 : value(streak.getLongestStreak());
        int freezes = streak == null ? 0 : value(streak.getFreezeCount());
        LocalDate last = streak == null ? null : streak.getLastCompletedDate();

        String status;
        if (offer != null) {
            status = "AT_RISK";
        } else if (current == 0) {
            status = "NONE";
        } else if (today.equals(last)) {
            status = "DONE_TODAY";
        } else {
            status = "ACTIVE";
        }

        FreezeOfferResponse offerView = null;
        if (offer != null) {
            long balance = credits.balance(userId);
            offerView = new FreezeOfferResponse(offer.date(), today.plusDays(1).atStartOfDay(zone).toInstant(),
                    offer.streakAtRisk(), freezes > 0, balance >= properties.freezePrice(), properties.freezePrice());
        }

        MissionDefinition next = missions.activeStreakMilestones().stream()
                .filter(value -> value(value.getTargetCount()) > current)
                .findFirst().orElse(null);
        return new StreakResponse(current, longest, status, last, freezes, properties.maxFreezes(), offerView,
                next == null ? null : value(next.getTargetCount()),
                next == null ? null : value(next.getRewardCredits()),
                week(userId, today));
    }

    private List<StreakDayResponse> week(Long userId, LocalDate today) {
        LocalDate from = today.minusDays(6);
        Map<LocalDate, UserDailyProgress> done = dailyProgress.findByUserIdAndProgressDateBetween(userId, from, today)
                .stream()
                .filter(value -> value.getTargetCompletedAt() != null)
                .collect(Collectors.toMap(UserDailyProgress::getProgressDate, value -> value, (left, right) -> left));
        Set<LocalDate> frozen = freezeDays.findUsedBetween(userId, from, today);
        List<StreakDayResponse> days = new ArrayList<>(7);
        for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
            String state;
            if (done.containsKey(day)) {
                state = "COMPLETED";
            } else if (frozen.contains(day)) {
                state = "FROZEN";
            } else if (day.equals(today)) {
                state = "TODAY";
            } else {
                state = "MISSED";
            }
            days.add(new StreakDayResponse(day, state));
        }
        return days;
    }

    private UserStreaks lock(Long userId) {
        return streaks.findForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("user_streaks row missing after insert"));
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }
}
