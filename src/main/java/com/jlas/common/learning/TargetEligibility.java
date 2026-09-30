package com.jlas.common.learning;

import java.util.List;

/** Immutable server decision for a requested JLPT learning-path target. */
public record TargetEligibility(
        boolean allowed,
        String requestedTarget,
        String recommendedTarget,
        List<String> stageLevels,
        String reasonCode,
        boolean requiresTargetConfirmation) {

    public TargetEligibility {
        stageLevels = stageLevels == null ? List.of() : List.copyOf(stageLevels);
    }
}
