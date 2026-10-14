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
    /** Ảnh đại diện + khung avatar + màu tên người bình luận (khung/màu mua ở Cửa hàng; null = không có). */
    private String avatarUrl;
    private String avatarFrame;
    private String nameStyle;
}
