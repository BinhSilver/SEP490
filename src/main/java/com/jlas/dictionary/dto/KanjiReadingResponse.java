package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/** Một cách đọc của Kanji (ON/KUN/NANORI/OTHER). */
@Data
@Builder
public class KanjiReadingResponse {
    private String readingType;
    private String readingText;
    /** Gợi ý "học trước" (cách đọc đầu tiên mỗi loại theo thứ tự KANJIDIC2), không phải tần suất sử dụng. */
    private Boolean isCommon;
}
