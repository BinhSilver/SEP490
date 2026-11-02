package com.jlas.jlptpath.dto;

import java.util.List;

/**
 * [JLAS-W0108] Public part of one check question — safe to send before the question is locked: no item codes,
 * no answers. {@code type} is PAIR_BOARD, MCQ, AUDIO_MCQ, ORDER or FILL_TEXT; {@code direction} is a
 * {@code JpathQuizDirection} name for vocabulary/kanji quiz questions, otherwise null; {@code timerSec} null = no timer.
 */
public record JpathQuestionView(String id, String type, String direction, String instructionVi, String prompt,
                                String promptFurigana, String audioUrl, List<JpathOptionView> options,
                                JpathBoardView board, Integer timerSec) {
}
