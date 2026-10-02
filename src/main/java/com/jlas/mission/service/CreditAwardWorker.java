package com.jlas.mission.service;

import com.jlas.mission.repository.PendingCreditAwardRepository;
import com.jlas.mission.repository.PendingCreditAwardRepository.PendingAward;
import com.jlas.mission.repository.RewardCelebrationRepository;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import com.jlas.moderation.credit.service.UserCreditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Applies one queued award to the ledger in its own transaction. */
@Component
@RequiredArgsConstructor
public class CreditAwardWorker {

    private final PendingCreditAwardRepository awards;
    private final RewardCelebrationRepository celebrations;
    private final UserCreditService credits;

    /** Demo switch that makes the credit step fail, to show that rewards survive an outage. */
    private volatile boolean simulateFailure;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void apply(Long awardId) {
        PendingAward award = awards.lock(awardId).orElse(null);
        if (award == null || award.completed()) {
            return;
        }
        if (simulateFailure) {
            throw new IllegalStateException("Simulated credit service failure");
        }
        credits.earn(award.userId(), award.amount(), CreditReason.valueOf(award.reason()),
                award.referenceType(), award.referenceId(), award.note());
        awards.markCompleted(awardId);
        if (award.celebrationKind() != null) {
            celebrations.add(award.userId(), "award:" + awardId, award.celebrationKind(),
                    award.celebrationTitle(), award.amount(), award.streak());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long awardId, String error) {
        awards.markFailed(awardId, error);
    }

    public boolean simulateFailure() {
        return simulateFailure;
    }

    public void simulateFailure(boolean enabled) {
        simulateFailure = enabled;
    }
}
