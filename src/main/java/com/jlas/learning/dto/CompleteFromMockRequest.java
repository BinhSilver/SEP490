package com.jlas.learning.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** [JLAS-W0108] Task 9 — the client names only the Mock Test attempt; pass/fail is never client-supplied. */
public record CompleteFromMockRequest(@NotNull @Positive Long attemptId) {
}
