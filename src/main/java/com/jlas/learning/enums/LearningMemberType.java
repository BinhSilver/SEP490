package com.jlas.learning.enums;

public enum LearningMemberType {
    VOCABULARY_ENTRY,
    KANJI,
    GRAMMAR_POINT,
    DIALOGUE_LINE,
    COMPREHENSION_QUESTION,
    /** [JLAS-W0108] 1 ký tự Hiragana/Katakana (content_item.id, content_type=READING dưới bài bảng
     * chữ cái) — canonical vẫn là content_item, không tạo bảng riêng cho từng ký tự. */
    FOUNDATION_CHARACTER
}
