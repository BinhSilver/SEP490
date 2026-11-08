package com.jlas.jlptpath.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * [JLAS-W0108] Play URL for JLPT path audio. Decision 39: words, examples and grammar pass {@code fallbackText = null},
 * so a missing file gives {@code null} and the page hides the play button; only kana falls back to TTS.
 */
public final class JpathAudio {

    private static final String AUDIO_PREFIX = "/uploads/content-audio/";
    private static final String TTS_PREFIX = "/api/dictionary/tts?text=";
    private static final int MAX_TTS_CODE_POINTS = 200;

    private JpathAudio() {
    }

    /**
     * @param audioPath    path under the content audio store (e.g. {@code learning-path/N5/JV-…mp3}), may be null
     * @param fallbackText text to read with TTS when the path is missing or unsafe, may be null
     * @return the file URL, the TTS URL, or {@code null} when there is nothing to play
     */
    public static String url(String audioPath, String fallbackText) {
        if (isSafePath(audioPath)) {
            return AUDIO_PREFIX + stripLeadingSlashes(audioPath);
        }
        if (fallbackText == null) {
            return null;
        }
        String text = fallbackText.strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > MAX_TTS_CODE_POINTS) {
            return null;
        }
        return TTS_PREFIX + URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean isSafePath(String path) {
        return path != null && !path.isBlank()
                && !path.contains("..") && !path.contains("\\") && !path.contains(":");
    }

    private static String stripLeadingSlashes(String path) {
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        return path.substring(start);
    }
}
