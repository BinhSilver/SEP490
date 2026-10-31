package com.jlas.shadowing.dto;

import java.util.Map;

/** Reactions of one comment after a change, and the one the current user now has on it (or null). */
public record CommentReactionDto(Long commentId, Map<String, Long> reactions, String myReaction) {
}
