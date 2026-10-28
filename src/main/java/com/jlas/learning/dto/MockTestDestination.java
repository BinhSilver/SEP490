package com.jlas.learning.dto;

/**
 * [JLAS-W0193] RC Task 7 — the Mock Test hand-off destination the server decided for this caller. The
 * browser never composes {@code url} itself and never trusts a query parameter to pick it — this record
 * is the only source of truth the summary page's "Thi thử JLPT" action may use.
 *
 * <p>{@code status} is {@link #STATUS_AVAILABLE}, {@link #STATUS_BLOCKED_INTEGRATION}, or
 * {@link #STATUS_NOT_APPLICABLE}. A missing
 * owner contract (the default, since no real Mock Test module exists in this codebase — see
 * docs/rules-and-business-logic.md) is reported as {@code BLOCKED_INTEGRATION}: an honest, expected state,
 * never a reason to fabricate a link. {@code url} is null unless {@code available} is true, and is always
 * pre-validated server-side (see {@code ConfiguredMockTestDestinationProvider}) as either an internal
 * relative route or an approved application origin — never an arbitrary redirect. {@code targetLevel} is
 * the only learner-related field ever echoed back — no internal path/package/activity id, no JWT.
 */
public record MockTestDestination(boolean available, String status, String url, String targetLevel,
        /** Owner 2026-10-26 (lỗi #3): why a configured Mock Test is still locked by the learner's progress
         * ({@code PRIOR_STAGE_INCOMPLETE}, {@code TARGET_STAGE_BELOW_THRESHOLD}, {@code BLOCKED_CONTENT}); null otherwise. */
        String reasonCode,
        Integer requiredTargetPercent) {

    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_BLOCKED_INTEGRATION = "BLOCKED_INTEGRATION";
    public static final String STATUS_NOT_APPLICABLE = "NOT_APPLICABLE";
    /** The Mock Test exists but the path has not reached the roadmap's eligibility rule yet. */
    public static final String STATUS_LOCKED_PROGRESS = "LOCKED_PROGRESS";

    public MockTestDestination {
        if (available && (url == null || url.isBlank())) {
            throw new IllegalArgumentException("An available Mock Test destination must have a url");
        }
        if (status == null) {
            status = available ? STATUS_AVAILABLE : STATUS_BLOCKED_INTEGRATION;
        }
    }

    public MockTestDestination(boolean available, String status, String url, String targetLevel) {
        this(available, status, url, targetLevel, null, null);
    }

    /** Same rule as the roadmap ({@code LearningFinalEligibilityService}): no link until the path is eligible. */
    public static MockTestDestination lockedByProgress(String targetLevel, String reasonCode, int requiredTargetPercent) {
        return new MockTestDestination(false, STATUS_LOCKED_PROGRESS, null, targetLevel, reasonCode, requiredTargetPercent);
    }

    /** The honest default/fallback: no owner contract, no fabricated link. */
    public static MockTestDestination blocked(String targetLevel) {
        return new MockTestDestination(false, STATUS_BLOCKED_INTEGRATION, null, targetLevel);
    }

    /** Communication goals are survey-only and never receive a JLPT Mock Test action. */
    public static MockTestDestination notApplicable() {
        return new MockTestDestination(false, STATUS_NOT_APPLICABLE, null, null);
    }
}
