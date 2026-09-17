package com.jlas.mission.service.impl;

import com.jlas.auth.entity.User;
import com.jlas.mission.dto.StreakSnapshot;
import com.jlas.mission.entity.UserStreaks;
import com.jlas.mission.repository.UserStreaksRepository;
import com.jlas.mission.service.StreakService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link StreakService}. */
@Service
@RequiredArgsConstructor
public class StreakServiceImpl implements StreakService {

    private final UserStreaksRepository streaksRepository;
    private final EntityManager em;
    private final Clock clock;

    @Override
    @Transactional
    public int recordCompletion(Long userId) {
        LocalDate today = LocalDate.now(clock);
        UserStreaks streak = streakOrNew(userId);
        if (today.equals(streak.getLastCompletedDate())) {
            return value(streak.getCurrentStreak());
        }
        boolean consecutive = streak.getLastCompletedDate() != null
                && streak.getLastCompletedDate().equals(today.minusDays(1));
        int current = consecutive ? value(streak.getCurrentStreak()) + 1 : 1;
        streak.setCurrentStreak(current);
        streak.setLongestStreak(Math.max(value(streak.getLongestStreak()), current));
        streak.setLastCompletedDate(today);
        streaksRepository.save(streak);
        return current;
    }

    @Override
    @Transactional(readOnly = true)
    public StreakSnapshot snapshot(Long userId) {
        return streaksRepository.findByUserId(userId)
                .map(streak -> new StreakSnapshot(value(streak.getCurrentStreak()), value(streak.getLongestStreak())))
                .orElse(new StreakSnapshot(0, 0));
    }

    private UserStreaks streakOrNew(Long userId) {
        return streaksRepository.findByUserId(userId)
                .orElseGet(() -> UserStreaks.builder()
                        .user(em.getReference(User.class, userId))
                        .build());
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }
}
