package com.jlas.learning.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** §2.32: the suggested passage to practise; the server re-checks it is still one of the learner's suggestions. */
public record ExtraPracticeStartRequest(@NotBlank @Size(max = 100) String passage) { }
