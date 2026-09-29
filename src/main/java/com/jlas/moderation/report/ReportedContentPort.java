package com.jlas.moderation.report;

import java.util.List;
import java.util.Optional;

/**
 * Contract implemented by each community feature once JLAS2-37 (report/state) is merged.
 * Moderation never touches feature tables directly; it asks the owning feature through this port,
 * the same way {@code ModerationTargetHandler} works for the AI review queue.
 */
public interface ReportedContentPort {

    boolean supports(String targetType);

    /** Targets currently TEMPORARILY_HIDDEN that still have open reports. */
    List<ReportedTarget> hiddenTargets();

    Optional<ReportedTarget> find(String targetType, Long targetId);

    /**
     * Moves the target from TEMPORARILY_HIDDEN to {@code decision.newState()} and closes its open
     * reports with the moderator, time, decision and reason. Called inside the moderation transaction.
     */
    void resolve(String targetType, Long targetId, ReportDecision decision);
}
