package com.jlas.shadowing.util;

import com.jlas.shadowing.dto.SubtitleDto;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Time-aware text comparison for captions and the independent audio transcript. */
public final class SubtitleQualityComparator {
    private SubtitleQualityComparator() {
    }

    public static Comparison compare(SubtitleDto source, List<SubtitleDto> transcript, double paddingSeconds) {
        if (source == null || source.getText() == null || source.getText().isBlank()) {
            return new Comparison(0d, "");
        }
        StringBuilder audioText = new StringBuilder();
        double sourceStart = valueOrZero(source.getStartTime()) - paddingSeconds;
        double sourceEnd = valueOrZero(source.getEndTime()) + paddingSeconds;
        for (SubtitleDto cue : transcript == null ? List.<SubtitleDto>of() : transcript) {
            if (cue.getText() == null || cue.getText().isBlank()) continue;
            if (valueOrZero(cue.getEndTime()) < sourceStart || valueOrZero(cue.getStartTime()) > sourceEnd) continue;
            if (!audioText.isEmpty()) audioText.append(' ');
            audioText.append(cue.getText());
        }
        String joined = audioText.toString();
        return new Comparison(similarity(source.getText(), joined), joined);
    }

    public static double similarity(String left, String right) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        if (normalizedLeft.isEmpty() || normalizedRight.isEmpty()) return 0d;
        if (normalizedLeft.equals(normalizedRight)) return 1d;
        int distance = levenshtein(normalizedLeft, normalizedRight);
        return Math.max(0d, 1d - (double) distance / Math.max(normalizedLeft.length(), normalizedRight.length()));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int index = 0; index <= right.length(); index++) previous[index] = index;
        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            current[0] = leftIndex;
            for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
                int cost = left.charAt(leftIndex - 1) == right.charAt(rightIndex - 1) ? 0 : 1;
                current[rightIndex] = Math.min(Math.min(current[rightIndex - 1] + 1, previous[rightIndex] + 1),
                        previous[rightIndex - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static double valueOrZero(Double value) {
        return value == null ? 0d : value;
    }

    public record Comparison(double similarityScore, String transcriptionText) {
    }
}
