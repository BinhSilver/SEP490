package com.jlas.assessment.enums;

import java.util.Locale;
import java.util.Set;

/** Các cấp độ kỳ thi JLPT. */
public enum JlptLevel {
    N5, N4, N3, N2, N1;

    /** [JLAS-W0108] Cấp độ đã phát hành cho lộ trình học. N2/N1 đang phát triển, chưa tạo path/đề/chứng nhận. */
    private static final Set<String> AVAILABLE_FOR_LEARNING = Set.of("N5", "N4", "N3");

    /** True nếu level này được hỗ trợ tạo lộ trình/đề. N2/N1 trả về false ở cả UI lẫn API. */
    public static boolean isAvailableForLearning(String level) {
        return level != null && AVAILABLE_FOR_LEARNING.contains(level.trim().toUpperCase(Locale.ROOT));
    }
}
