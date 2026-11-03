package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathRetypeVerdict;

/**
 * [JLAS-W0108] Result of one study event: {@code status} APPLIED, DUPLICATE (the client event id was recorded before)
 * or REJECTED with a {@code reason} (SESSION_CLOSED, STEP_MISMATCH, ACTION_MISMATCH, RATING_INVALID). APPLIED
 * with reason ITEM_NOT_FOUND (the step's item is gone) or ALREADY_KNOWN (a NEW card known since the queue was made)
 * means the step was passed over. A RETYPE carries {@code verdict} and the {@code expected} reading; {@code checkToken} is the SKIP_ONE
 * check of a SKIP_KNOWN.
 */
public record JpathEventOutcome(String clientEventId, String status, String reason, JpathRetypeVerdict verdict,
                                String expected, String checkToken) {
}
