package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathRetypeVerdict;

/**
 * [JLAS-W0108] Result of one study event: {@code status} APPLIED, DUPLICATE (the client event id was recorded before)
 * or REJECTED with a {@code reason} (SESSION_CLOSED, STEP_MISMATCH, ACTION_MISMATCH, RATING_INVALID, ITEM_NOT_FOUND,
 * NOT_READY). A RETYPE carries {@code verdict} and the {@code expected} reading; {@code checkToken} is the SKIP_ONE
 * check of a SKIP_KNOWN.
 */
public record JpathEventOutcome(String clientEventId, String status, String reason, JpathRetypeVerdict verdict,
                                String expected, String checkToken) {
}
