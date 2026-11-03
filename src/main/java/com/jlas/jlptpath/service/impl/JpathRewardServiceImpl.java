package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.service.JpathBadgeService;
import com.jlas.jlptpath.service.JpathRewardService;
import com.jlas.jlptpath.util.JpathRewardPolicy;
import com.jlas.mission.service.CreditAwardService;
import com.jlas.mission.service.StreakService;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * [JLAS-W0108] Pays the JLPT path rewards inside the caller's transaction: CP is queued with {@link CreditAwardService}
 * (the ledger's reference key keeps a repeat from paying twice), the streak goes through {@link StreakService} and
 * badges through {@link JpathBadgeService}. Takes no jpath lock (the callers hold the path lock). A transaction that
 * queued CP applies the learner's open awards once, after commit.
 */
@Service
@RequiredArgsConstructor
public class JpathRewardServiceImpl implements JpathRewardService {

    /** Transaction resource marking that the after-commit apply is already registered. */
    private static final Object APPLY_KEY = JpathRewardServiceImpl.class;

    private final CreditAwardService awards;
    private final StreakService streaks;
    private final JpathBadgeService badges;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onTabDayCompleted(Long userId, LocalDate day, JpathTab tab, boolean rewardEligible) {
        if (tab == JpathTab.SKILL || !rewardEligible) {
            return;
        }
        pay(userId, JpathRewardPolicy.TAB_DAY, JpathRewardPolicy.REF_TAB_DAY, JpathRewardPolicy.tabDayRef(day, tab),
                "Xong phần " + label(tab) + " hôm nay");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReviewsCleared(Long userId, LocalDate day) {
        pay(userId, JpathRewardPolicy.REVIEW_DAY, JpathRewardPolicy.REF_REVIEW_DAY, day.toEpochDay(),
                "Ôn hết thẻ đến hạn hôm nay");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onDayCompleted(Long userId, LocalDate day, JpathDayKind kind, boolean rewardEligible) {
        if (kind == JpathDayKind.REST) {
            return;
        }
        streaks.recordCompletion(userId, day);
        if (rewardEligible) {
            if (kind == JpathDayKind.SKILL) {
                pay(userId, JpathRewardPolicy.SKILL_DAY, JpathRewardPolicy.REF_SKILL_DAY, day.toEpochDay(),
                        "Xong ngày kỹ năng");
            } else {
                pay(userId, JpathRewardPolicy.DAY, JpathRewardPolicy.REF_DAY, day.toEpochDay(),
                        "Xong cả phần hôm nay");
            }
        }
        evaluateBadges(userId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onProgressChanged(Long userId) {
        evaluateBadges(userId);
    }

    private void evaluateBadges(Long userId) {
        if (!badges.evaluate(userId).isEmpty()) {
            applyAfterCommit(userId);
        }
    }

    private void pay(Long userId, int amount, String refType, long refId, String note) {
        awards.enqueue(userId, amount, CreditReason.STUDY_REWARD, refType, refId, note, null, null, null);
        applyAfterCommit(userId);
    }

    /** Registers one after-commit {@code applyOpenAwards} per transaction; without synchronization nothing happens. */
    private void applyAfterCommit(Long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.getResource(APPLY_KEY) != null) {
            return;
        }
        TransactionSynchronizationManager.bindResource(APPLY_KEY, userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                awards.applyOpenAwards(userId);
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(APPLY_KEY);
            }
        });
    }

    private static String label(JpathTab tab) {
        return switch (tab) {
            case VOCAB -> "Từ vựng";
            case KANJI -> "Kanji";
            case GRAMMAR -> "Ngữ pháp";
            case KANA -> "Chữ cái";
            case SKILL -> "Kỹ năng";
        };
    }
}
