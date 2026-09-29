package com.jlas.mission.service;

import com.jlas.mission.repository.PendingCreditAwardRepository;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Credit awards earned by learning activity. Awards are queued with the progress that earned them
 * and applied to the central ledger ({@code UserCreditService}) afterwards; failures are retried
 * with backoff, and the ledger's reference key keeps retries from paying twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditAwardService {

    private static final int BATCH_SIZE = 100;

    private final PendingCreditAwardRepository awards;
    private final CreditAwardWorker worker;

    /** Must run inside the transaction that records the progress, so both commit or neither does. */
    public void enqueue(Long userId, int amount, CreditReason reason, String referenceType, Long referenceId,
                        String note, String celebrationKind, String celebrationTitle, Integer streak) {
        if (amount <= 0) {
            return;
        }
        awards.enqueue(userId, amount, reason.name(), referenceType, referenceId, note,
                celebrationKind, celebrationTitle, streak);
    }

    /** Applies the learner's open awards right away; anything that fails stays queued for the retry job. */
    public void applyOpenAwards(Long userId) {
        apply(awards.findOpenIdsForUser(userId));
    }

    public int openAwards(Long userId) {
        return awards.countOpen(userId);
    }

    @Scheduled(initialDelay = 30_000, fixedDelayString = "${mission.award-retry-delay-ms:60000}")
    public void retryDueAwards() {
        apply(awards.findDueIds(BATCH_SIZE));
    }

    private void apply(List<Long> ids) {
        for (Long id : ids) {
            try {
                worker.apply(id);
            } catch (RuntimeException ex) {
                log.warn("Credit award {} failed, will retry: {}", id, ex.getMessage());
                try {
                    worker.recordFailure(id, ex.getClass().getSimpleName() + ": " + ex.getMessage());
                } catch (RuntimeException nested) {
                    log.error("Could not record failure of credit award {}", id, nested);
                }
            }
        }
    }
}
