package com.jlas.jlptpath.dto;

import java.util.List;

/**
 * [JLAS-W0108] Public part of one check question — safe to send before the question is locked: no item codes,
 * no answers. {@code type} is PAIR_BOARD, MCQ, AUDIO_MCQ, ORDER or FILL_TEXT; {@code direction} is a
 * {@code JpathQuizDirection} name for vocabulary/kanji quiz questions, otherwise null; {@code audioSlot} (AUDIO_MCQ,
 * 1-based) is opaque — the file is served by the check token and slot, its path stays server-side;
 * {@code timerSec} null = no timer.
 */
public record JpathQuestionView(String id, String type, String direction, String instructionVi, String prompt,
                                String promptFurigana, Integer audioSlot, List<JpathOptionView> options,
                                JpathBoardView board, Integer timerSec) {
}
