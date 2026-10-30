package com.jlas.mission.service;

import com.jlas.common.event.CreditEarned;
import com.jlas.mission.repository.RewardCelebrationRepository;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns credits earned anywhere in the app into a "gift" celebration shown on the learner's next page.
 * Rewards that already have their own animation are skipped: mission claims (animated right away
 * from the claim response), the daily goal and streak milestones (queued with their own scene).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreditCelebrationListener {

    private static final Set<String> OWN_CELEBRATION = Set.of("DAILY_GOAL_REWARD", "STREAK_MILESTONE_REWARD");
    private static final String REF_USER_MISSION = "USER_MISSION";
    private static final Map<String, String> TITLES = Map.of(
            "CREATOR_REWARD", "Thưởng người sáng tạo",
            "WORD_CONTRIBUTION_REWARD", "Thưởng đóng góp từ điển",
            "ADMIN_GRANT", "Quà từ quản trị viên",
            "REFUND", "Hoàn điểm",
            "STUDY_REWARD", "Thưởng học tập",
            "QUIZ_REWARD", "Thưởng làm quiz",
            "SHADOWING_REWARD", "Thưởng Shadowing",
            "DAILY_CHECKIN", "Thưởng điểm danh");

    private final RewardCelebrationRepository celebrations;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCreditEarned(CreditEarned event) {
        if (event.amount() <= 0 || OWN_CELEBRATION.contains(event.reason())
                || REF_USER_MISSION.equals(event.referenceType())) {
            return;
        }
        try {
            String key = event.referenceType() != null && event.referenceId() != null
                    ? "credit:" + event.reason() + ":" + event.referenceType() + ":" + event.referenceId()
                    : "credit:" + event.reason() + ":" + UUID.randomUUID();
            String title = event.note() != null && !event.note().isBlank()
                    ? event.note() : TITLES.getOrDefault(event.reason(), "Bạn nhận được điểm thưởng");
            celebrations.add(event.userId(), truncate(key, 160), "CREDIT_EARNED", truncate(title, 160),
                    event.amount(), null);
        } catch (RuntimeException ex) {
            // A missing animation must never affect the credit itself.
            log.warn("Could not queue credit celebration for user {}: {}", event.userId(), ex.getMessage());
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
