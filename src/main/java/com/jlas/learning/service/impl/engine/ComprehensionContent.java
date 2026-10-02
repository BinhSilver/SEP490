package com.jlas.learning.service.impl.engine;

import com.jlas.learning.dto.ComprehensionExercise;
import com.jlas.learning.dto.ComprehensionQuestionSpec;

/** Canonical question plus its source-group identity. Internal only; answer fields never enter public DTOs. */
public record ComprehensionContent(ComprehensionExercise exercise, ComprehensionQuestionSpec question) { }
