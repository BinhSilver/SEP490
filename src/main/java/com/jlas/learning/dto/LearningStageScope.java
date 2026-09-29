package com.jlas.learning.dto;

import java.util.List;

/**
 * [JLAS-W0108] P6 — phạm vi giáo trình của một chặng (stage) trong lộ trình nhiều cấp.
 * Mỗi chặng nhắm đúng một cấp mục tiêu; các chặng nối tiếp theo thứ tự (D5). Trường
 * {@code curriculumVersion} là fingerprint của bộ sách đã duyệt cho cấp đó (P8 ghim manifest thật).
 */
public record LearningStageScope(String level, String curriculumVersion, List<Long> goalTextbookIds) {
    public LearningStageScope {
        goalTextbookIds = List.copyOf(goalTextbookIds);
    }
}
