package com.jlas.learning.service.impl;

import java.math.BigDecimal;
import com.jlas.learning.dto.TagAssessmentResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WeakTagPolicy {
    private final int minimumQuestions;
    private final BigDecimal threshold;

    public WeakTagPolicy(@Value("${jlas.learning.selection.minimum-assessed-questions:3}") int minimumQuestions,
            @Value("${jlas.learning.selection.wrong-ratio-threshold:0.5}") BigDecimal threshold) {
        if (minimumQuestions < 1 || threshold == null || threshold.signum() <= 0
                || threshold.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Weak-tag policy requires a positive sample size and a ratio in (0, 1].");
        }
        this.minimumQuestions = minimumQuestions;
        this.threshold = threshold.stripTrailingZeros();
    }

    public boolean hasEnoughEvidence(TagAssessmentResult result) {
        return result.assessedCount() >= minimumQuestions;
    }

    public boolean isWeak(TagAssessmentResult result) {
        return hasEnoughEvidence(result) && BigDecimal.valueOf(result.wrongCount())
                .compareTo(threshold.multiply(BigDecimal.valueOf(result.assessedCount()))) >= 0;
    }

    public String version() {
        return "jlpt-two-phase-v1:min=" + minimumQuestions + ":ratio=" + threshold.toPlainString();
    }
}
