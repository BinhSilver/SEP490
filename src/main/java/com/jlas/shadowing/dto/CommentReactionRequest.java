package com.jlas.shadowing.dto;

import lombok.Data;

/** Reaction to leave on a comment: LIKE, LOVE, HAHA, WOW, SAD or CLAP. */
@Data
public class CommentReactionRequest {
    private String reaction;
}
