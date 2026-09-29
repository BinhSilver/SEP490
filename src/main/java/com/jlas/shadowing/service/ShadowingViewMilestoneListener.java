package com.jlas.shadowing.service;

import com.jlas.shadowing.model.ShadowingViewCounted;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Runs milestone rewards after the view commits, so a reward failure never loses the view. */
@Component
@RequiredArgsConstructor
public class ShadowingViewMilestoneListener {
    private static final Logger log = LoggerFactory.getLogger(ShadowingViewMilestoneListener.class);

    private final ShadowingViewMilestoneService milestones;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onViewCounted(ShadowingViewCounted event) {
        try {
            milestones.rewardReachedMilestones(event);
        } catch (RuntimeException ex) {
            // The milestone row rolled back with the reward, so the next counted view retries it.
            log.warn("View milestone reward failed for video {} owner {}: {}",
                    event.videoId(), event.ownerId(), ex.getMessage());
        }
    }
}
