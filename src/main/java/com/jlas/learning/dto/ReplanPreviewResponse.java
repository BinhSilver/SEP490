package com.jlas.learning.dto;

import java.util.List;

/**
 * [JLAS-W0122] Kết quả xem trước replan (không ghi gì). Tóm tắt các thay đổi sẽ áp dụng và
 * lịch mới để learner quyết định xác nhận hay hủy.
 */
public record ReplanPreviewResponse(
        String mode,
        boolean changesApply,
        int remediationAdded,
        int masteredRemoved,
        int newTotalLessons,
        int newTotalDays,
        int newTotalMinutes,
        List<String> addedSummaries,
        List<String> removedSummaries,
        String explanation) {
    public ReplanPreviewResponse {
        addedSummaries = List.copyOf(addedSummaries);
        removedSummaries = List.copyOf(removedSummaries);
    }
}
