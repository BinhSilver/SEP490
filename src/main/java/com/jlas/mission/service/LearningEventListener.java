package com.jlas.mission.service;

import com.jlas.common.event.LearningEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Consumes learning events after the producer's transaction commits. The learner's lesson or quiz
 * is already saved at that point, so a failure here can never roll it back.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LearningEventListener {

    private final LearningEventProcessor processor;
    private final CreditAwardService awards;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLearningEvent(LearningEvent event) {
        try {
            processor.process(event);
        } catch (RuntimeException ex) {
            log.error("Could not apply learning event {} for user {}", event.eventId(), event.userId(), ex);
            return;
        }
        awards.applyOpenAwards(event.userId());
    }
}
