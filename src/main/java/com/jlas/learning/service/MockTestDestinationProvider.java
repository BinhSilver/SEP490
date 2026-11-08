package com.jlas.learning.service;

import com.jlas.learning.dto.MockTestDestination;

/**
 * [JLAS-W0193] RC Task 7 — the seam a future, real Mock Test module would implement to receive a hand-off
 * from the Interactive Learning Path summary page. No such module exists in this codebase today
 * (re-audited fresh at RC Task 7 time, same conclusion as the Task 10 audit before it); the shipped
 * default implementation ({@code ConfiguredMockTestDestinationProvider}) always reports itself
 * unavailable unless a real destination has been explicitly configured and passes URL validation.
 *
 * <p>The server alone decides availability and the destination URL — the browser must never compose or
 * trust a client-supplied value for either (RC plan Task 7, rules 3-4).
 */
public interface MockTestDestinationProvider {

    /**
     * Resolves the Mock Test hand-off destination for the given target JLPT level ({@code null} allowed
     * when the caller's path has none). Implementations must never return an unvalidated URL — see
     * {@link MockTestDestination} for the exact contract a caller may rely on, and must pass only fields
     * a real owner contract would explicitly accept (today: just the target level) — never an internal
     * path/package/activity id, never a JWT.
     */
    MockTestDestination resolveDestination(String targetLevel);
}
