package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/** Mẫu ngữ pháp của một bài học. */
@Data
@Builder
public class GrammarPointResponse {
    private Long id;
    private String pattern;
    private String meaning;
    private String example;
    private String note;
    private Integer orderIndex;

    /** Tag KNOWLEDGE gắn với mẫu, dùng ở màn hình biên soạn; null ở màn hình học. */
    private Long tagId;

    /** Số câu ví dụ đang trỏ tới mẫu; màn hình biên soạn dùng để biết mẫu nào xoá được. Null ở màn hình học. */
    private Long exampleCount;
}
