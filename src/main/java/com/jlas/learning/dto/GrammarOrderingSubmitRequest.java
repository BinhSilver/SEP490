package com.jlas.learning.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** [JLAS-W0193] Task 6 — the learner's submitted word order: a permutation of the token ids shown for the
 * point's ordering practice (never the words themselves, so grading only ever compares server-known ids). */
public record GrammarOrderingSubmitRequest(@NotEmpty List<Integer> order) { }
