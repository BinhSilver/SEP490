package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Một dòng kết quả khi người biên soạn tìm từ để gắn vào bài.
 * Gọn hơn {@link LessonVocabularyResponse} (không kèm audio) vì đây là danh sách để chọn, không phải
 * nội dung để học.
 * <p>
 * {@code attachedToLesson} cho biết từ này đã nằm trong bài đang soạn hay chưa, để giao diện không mời
 * gắn lại một từ đã có.
 */
@Data
@Builder
public class VocabularySearchItem {
    private Long id;
    private String expression;
    private String reading;
    private String readingRomaji;
    private String meaning;
    private String wordType;
    private String jlptLevel;
    private Boolean attachedToLesson;
}
