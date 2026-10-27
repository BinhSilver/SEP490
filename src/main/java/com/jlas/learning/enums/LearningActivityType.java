package com.jlas.learning.enums;

public enum LearningActivityType {
    VOCABULARY,
    KANJI,
    GRAMMAR,
    DIALOGUE,
    READING,
    LISTENING,
    /** [JLAS-W0108] Bảng chữ cái (Hiragana/Katakana) cho người học "from-zero" (BELOW_N5). Chỉ xuất
     * hiện trong package WARM_UP, không phải 1 trong 4 phần bắt buộc của target package (spec §2.2). */
    FOUNDATION
}
