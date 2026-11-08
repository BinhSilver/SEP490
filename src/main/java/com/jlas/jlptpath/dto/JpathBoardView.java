package com.jlas.jlptpath.dto;

import java.util.List;

/** [JLAS-W0108] A pair-matching board: left and right columns, each in its own shuffled order. */
public record JpathBoardView(String boardId, List<JpathOptionView> left, List<JpathOptionView> right) {
}
