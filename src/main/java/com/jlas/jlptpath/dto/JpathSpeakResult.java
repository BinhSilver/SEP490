package com.jlas.jlptpath.dto;

import java.util.List;

/**
 * [JLAS-W0108] Pronunciation score of one "Luyện nói theo" attempt (Azure pronunciation assessment, 0–100). {@code words}
 * = each word as Azure split it, with its accuracy and error type (None, Mispronunciation, Omission, Insertion);
 * {@code attemptsLeft} = scored attempts left today for this learner.
 */
public record JpathSpeakResult(Double pronunciation, Double accuracy, Double fluency, Double completeness,
        String recognizedText, String feedbackVi, List<Word> words, int attemptsLeft) {

    public record Word(String word, Double accuracy, String errorType) {
    }
}
