package com.jlas.auth.service;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether a learner's Pro (Premium) is active: a plan other than FREE that has not expired yet.
 * One rule for every paywall (AI Call quota, CP earn cap, quick lookup, Onyomi, pronunciation).
 */
@Service
@RequiredArgsConstructor
public class PremiumAccess {
    static final String FREE_PLAN = "FREE";

    private final UserRepository users;

    @Transactional(readOnly = true)
    public boolean isActive(Long userId) {
        return userId != null && users.findById(userId)
                .map(user -> isActive(user, LocalDateTime.now()))
                .orElse(false);
    }

    public static boolean isActive(User user, LocalDateTime now) {
        return user.getPremiumPlan() != null && !FREE_PLAN.equalsIgnoreCase(user.getPremiumPlan().getName())
                && user.getPremiumExpiredAt() != null && user.getPremiumExpiredAt().isAfter(now);
    }
}
