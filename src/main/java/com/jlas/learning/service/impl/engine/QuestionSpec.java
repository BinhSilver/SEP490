package com.jlas.learning.service.impl.engine;

import java.util.List;

/** [JLAS-W0193] One deterministically-generated micro-check question. {@code explanation} doubles as the
 * canonical correct-answer text used for post-submit review; it never reaches the client before submit. */
public record QuestionSpec(long questionId, String type, String prompt, List<String> options,
        int correctOption, String explanation) {
}
