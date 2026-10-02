package com.jlas.learning.service;

import com.jlas.learning.dto.InteractivePathSummaryResponse;

/** [JLAS-W0193] Task 10 — real content for the interactive path's end-of-journey summary. */
public interface InteractivePathSummaryService {
    /** Loads the caller's most recent V2 path (regardless of ACTIVE/COMPLETED status) and aggregates
     * first/latest/best per skill, Dialogue completion, Reading/Listening evidence and a readiness
     * confidence that explains its own evidence. Never accepts a path id from the caller — ownership is
     * entirely principal-derived. */
    InteractivePathSummaryResponse summary(Long userId);
}
