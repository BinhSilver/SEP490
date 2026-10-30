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
    /** Top-level comment this one replies to; null for a top-level comment. */
    private Long parentId;
    /** Replies of a top-level comment, oldest first; empty for replies themselves. */
    private java.util.List<CommentDto> replies = new java.util.ArrayList<>();
    /** Reaction (LIKE, LOVE, HAHA, WOW, SAD, CLAP) → how many people left it. */
    private java.util.Map<String, Long> reactions = new java.util.LinkedHashMap<>();
    /** The reaction the current user left on this comment, or null. */
    private String myReaction;
}
