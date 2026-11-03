package com.jlas.jlptpath.dto;

import java.util.List;

/** [JLAS-W0108] Outcomes of a study event batch (in input order) and the session after it. */
public record JpathEventBatchResult(List<JpathEventOutcome> outcomes, JpathSessionView session) {
}
