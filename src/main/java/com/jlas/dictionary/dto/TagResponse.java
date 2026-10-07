package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Tag phẳng để đổ dropdown ở màn hình quản lý nội dung.
 * Khác {@link TopicResponse} (dành riêng cho chủ đề SITUATION và có kèm lessonCount).
 */
@Data
@Builder
public class TagResponse {
    private Long id;
    private String code;
    private String name;
    private String tagType;
}
