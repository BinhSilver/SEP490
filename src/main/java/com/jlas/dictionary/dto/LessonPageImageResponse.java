package com.jlas.dictionary.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [JLAS2-17] Một ảnh trang từ vựng / ngữ pháp / kanji của bài (lesson_page_image). tab = VOCAB / GRAMMAR / KANJI;
 * imageUrl tương đối trong content.audio.path (trang học ghép tiền tố như ảnh câu hỏi).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LessonPageImageResponse {
    private String tab;
    private String imageUrl;
    private int orderIndex;
}
