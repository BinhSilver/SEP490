package com.jlas.jlptpath.dto;

import jakarta.validation.constraints.NotBlank;

/** [JLAS-W0108] One pick on a pair board: left and right option ids (l1.., r1..) of board {@code boardId}. */
public record JpathPairPickRequest(@NotBlank String boardId, @NotBlank String leftId, @NotBlank String rightId) {
}
