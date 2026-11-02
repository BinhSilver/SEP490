package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.dto.JpathBadgeView;
import com.jlas.jlptpath.entity.JpathUserBadge;
import com.jlas.jlptpath.entity.JpathUserLevelId;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathBadge;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.repository.JpathUserBadgeRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathBadgeService;
import com.jlas.jlptpath.util.JpathRewardPolicy;
import com.jlas.mission.repository.UserStreaksRepository;
import com.jlas.mission.service.CreditAwardService;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Badge rules: STREAK_n = current streak ≥ n; KNOWN_n = VOCAB items counted (any level) ≥ n; KANA_DONE =
 * the active path has kana_completed_at; N5_SKILL_STAGE = its N5 level has skill_practice_at. A badge is inserted with
 * INSERT IGNORE; only the call whose insert wins queues the badge CP, so a race never pays twice. Takes no lock.
 */
@Service
@RequiredArgsConstructor
public class JpathBadgeServiceImpl implements JpathBadgeService {

    private static final String N5 = "N5";

    private final JpathUserBadgeRepository userBadges;
    private final JpathUserItemRepository userItems;
    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository levels;
    private final UserStreaksRepository streaks;
    private final CreditAwardService awards;

    @Override
    @Transactional(readOnly = true)
    public List<JpathBadgeView> badges(Long userId) {
        Map<String, JpathUserBadge> earned = earnedByCode(userId);
        Progress progress = progress(userId);
        List<JpathBadgeView> views = new ArrayList<>();
        for (JpathBadge badge : JpathBadge.values()) {
            JpathUserBadge row = earned.get(badge.name());
            int value = row != null ? badge.goal() : (int) Math.min(progress.of(badge), badge.goal());
            views.add(new JpathBadgeView(badge.name(), badge.titleVi(), badge.descriptionVi(), row != null,
                    row == null ? null : row.getEarnedAt(), value, badge.goal()));
        }
        return views;
    }

    @Override
    @Transactional
    public List<String> evaluate(Long userId) {
        Map<String, JpathUserBadge> earned = earnedByCode(userId);
        if (earned.size() >= JpathBadge.values().length) {
            return List.of();
        }
        Progress progress = progress(userId);
        List<String> granted = new ArrayList<>();
        for (JpathBadge badge : JpathBadge.values()) {
            if (earned.containsKey(badge.name()) || progress.of(badge) < badge.goal()) {
                continue;
            }
            if (userBadges.insertIgnore(userId, badge.name()) == 1) {
                awards.enqueue(userId, JpathRewardPolicy.BADGE, CreditReason.STUDY_REWARD, JpathRewardPolicy.REF_BADGE,
                        (long) badge.ordinal(), "Huy hiệu mới: " + badge.titleVi(), null, null, null);
                granted.add(badge.name());
            }
        }
        return granted;
    }

    private Map<String, JpathUserBadge> earnedByCode(Long userId) {
        return userBadges.findByUserId(userId).stream()
                .collect(Collectors.toMap(JpathUserBadge::getBadgeCode, Function.identity(), (a, b) -> a));
    }

    private Progress progress(Long userId) {
        int streak = streaks.findByUserId(userId).map(s -> s.getCurrentStreak() == null ? 0 : s.getCurrentStreak())
                .orElse(0);
        long known = userItems.countByUserIdAndItemTypeAndCountedAtIsNotNull(userId, JpathItemType.VOCAB);
        Optional<JpathUserPath> path = paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE);
        boolean kana = path.map(JpathUserPath::getKanaCompletedAt).isPresent();
        boolean skillStage = path.flatMap(p -> levels.findById(new JpathUserLevelId(p.getId(), N5)))
                .map(l -> l.getSkillPracticeAt() != null).orElse(false);
        return new Progress(streak, known, kana, skillStage);
    }

    private record Progress(int streak, long known, boolean kana, boolean skillStage) {
        long of(JpathBadge badge) {
            return switch (badge) {
                case STREAK_7, STREAK_30 -> streak;
                case KNOWN_100, KNOWN_500, KNOWN_1000 -> known;
                case KANA_DONE -> kana ? 1 : 0;
                case N5_SKILL_STAGE -> skillStage ? 1 : 0;
            };
        }
    }
}
