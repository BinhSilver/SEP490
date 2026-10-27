package com.jlas.assessment.dto;

import java.util.List;

public record JlptHistoryPageResponse(
        List<JlptHistoryEntryResponse> items,
        int page,
        int size,
        boolean hasMore) {
}
