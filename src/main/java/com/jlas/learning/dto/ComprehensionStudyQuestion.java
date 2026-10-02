package com.jlas.learning.dto;

import java.util.List;

public record ComprehensionStudyQuestion(Long id, String prompt, List<String> options) {
    public ComprehensionStudyQuestion { options = List.copyOf(options); }
}
