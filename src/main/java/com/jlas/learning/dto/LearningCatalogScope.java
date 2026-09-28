package com.jlas.learning.dto;

import java.util.List;

// Ordered textbook IDs resolved by the server's curriculum integration, not by Smart Test scores.
public record LearningCatalogScope(List<Long> warmUpTextbookIds, List<Long> goalTextbookIds,
        List<LearningStageScope> stages) {
    public LearningCatalogScope {
        warmUpTextbookIds = List.copyOf(warmUpTextbookIds);
        goalTextbookIds = List.copyOf(goalTextbookIds);
        stages = List.copyOf(stages);
    }

    /**
     * [JLAS-W0108] P6 — tương thích ngược cho caller chỉ cần hai danh sách sách (test/adapters cũ,
     * lộ trình một chặng ngầm định); {@code stages} rỗng = chưa phân chặng.
     */
    public LearningCatalogScope(List<Long> warmUpTextbookIds, List<Long> goalTextbookIds) {
        this(warmUpTextbookIds, goalTextbookIds, List.of());
    }
}
