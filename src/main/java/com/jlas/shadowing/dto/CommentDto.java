package com.jlas.shadowing.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class CommentDto {
    private Long id;
    private Long videoId;
    private String clientId;
    private String displayName;
    private String content;
    private Integer rating;
    private LocalDateTime createdAt;
    private boolean own;
}
