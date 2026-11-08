package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Một cách đọc của Kanji (ON/KUN/NANORI/OTHER).
 *
 * <p>Hai cờ cùng nghĩa nhưng khác tên JSON vì hai trang đang đọc hai tên khác nhau:
 * {@code isCommon} (trang bài học giáo trình — textbook-lesson.js) và {@code common}
 * (trang tra từ điển — dictionary-page.js). Mapper luôn gán cả hai cùng giá trị.</p>
 */
@Data
@Builder
public class KanjiReadingResponse {
    private String readingType;
    private String readingText;
    /** Gợi ý "học trước" (cách đọc đầu tiên mỗi loại theo thứ tự KANJIDIC2), không phải tần suất sử dụng. */
    private Boolean isCommon;
    private boolean common;
}
