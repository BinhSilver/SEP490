package com.jlas.shadowing.dto.practice;

import com.jlas.shadowing.model.practice.PracticeMode;
import com.jlas.shadowing.model.practice.PracticeOrder;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.Set;

/**
 * {@code selectionCounts}: số câu muốn luyện cho từng mức của từng dạng, khoá "MỨC:DẠNG" như {@code selections}
 * (vd. {"EASY:GAP": 2, "MEDIUM:GAP": 1, "EASY:DETAIL": 3}), chỉ cho chế độ LISTENING. Bỏ trống thì lấy
 * {@code questionCount} câu trải đều các lựa chọn như trước.
 */
public record CreatePracticeSessionRequest(
        @NotNull Long videoId,
        @NotNull PracticeMode mode,
        PracticeOrder order,
        @Min(1) @Max(500) Integer questionCount,
        boolean allQuestions,
        Set<String> levels,
        Set<String> kinds,
        Set<String> selections,
        Map<String, @NotNull @Min(0) @Max(500) Integer> selectionCounts) {
    public CreatePracticeSessionRequest(Long videoId, PracticeMode mode, PracticeOrder order,
                                        Integer questionCount, boolean allQuestions,
                                        Set<String> levels, Set<String> kinds) {
        this(videoId, mode, order, questionCount, allQuestions, levels, kinds, null, null);
    }

    public CreatePracticeSessionRequest(Long videoId, PracticeMode mode, PracticeOrder order,
                                        Integer questionCount, boolean allQuestions,
                                        Set<String> levels, Set<String> kinds, Set<String> selections) {
        this(videoId, mode, order, questionCount, allQuestions, levels, kinds, selections, null);
    }
}
