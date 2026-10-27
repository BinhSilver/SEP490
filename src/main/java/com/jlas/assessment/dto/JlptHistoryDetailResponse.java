package com.jlas.assessment.dto;

import java.util.List;
import java.util.Map;

public record JlptHistoryDetailResponse(
        JlptHistoryEntryResponse attempt,
        Map<Long, Integer> answers,
        Map<Long, List<Integer>> sentenceOrders) {
}
