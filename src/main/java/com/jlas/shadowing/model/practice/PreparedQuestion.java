package com.jlas.shadowing.model.practice;

import java.util.List;

public record PreparedQuestion(
        Long id, Long videoId, Long subtitleId, List<Long> sourceIds,
        String kind, String level, String prompt, String answer,
        List<String> choices, String explanation, double startTime, double endTime,
        String sourceHash, int occurrence, String contentHash, String status) {
}
