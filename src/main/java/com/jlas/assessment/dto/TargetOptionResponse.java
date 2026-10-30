package com.jlas.assessment.dto;

import java.util.List;

public record TargetOptionResponse(
        String targetLevel,
        boolean allowed,
        String recommendedTarget,
        List<String> stageLevels,
        String reasonCode,
        boolean requiresTargetConfirmation) {
}
