package com.jlas.shadowing.dto;

import lombok.Data;

@Data
public class CommentRequest {
    private String clientId;
    private String displayName;
    private String content;
    private Integer rating;
}
