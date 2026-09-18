package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/** Chủ đề học (tag tag_type = SITUATION) kèm số bài người dùng hiện tại được xem. */
@Data
@Builder
public class TopicResponse {
    private Long id;
    private String code;
    private String name;
    private Long lessonCount;
}
