package com.jlas.moderation.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

/** Response thông tin bình luận nội dung. */
@Data
@Builder
public class ContentCommentResponse {
    private Long id;
    private Long userId;
    private String content;
    private LocalDateTime createdAt;
}
