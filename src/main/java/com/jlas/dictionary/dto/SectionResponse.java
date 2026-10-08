package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Phần nội dung của một bài học, dùng ở màn hình biên soạn.
 * {@code contentItemCount} để giao diện biết phần nào còn nội dung (xoá cứng sẽ bị chặn).
 */
@Data
@Builder
public class SectionResponse {
    private Long id;
    private String title;
    private String sectionType;
    private Integer orderIndex;
    private Long contentItemCount;
}
