package com.jlas.shadowing.util.youtube;

import com.jlas.shadowing.dto.SubtitleDto;

import java.util.List;

public final class SubtitleAligner {

    private SubtitleAligner() { }

    public static void attachTranslations(List<SubtitleDto> japanese, List<SubtitleDto> translations) {
        if (japanese == null || translations == null || translations.isEmpty()) return;
        for (SubtitleDto ja : japanese) {
            String text = findBestTranslation(ja.getStartTime(), ja.getEndTime(), translations);
            if (text != null && !text.isBlank()) {
                ja.setTranslation(text);
            }
        }
    }

    private static String findBestTranslation(double start, double end, List<SubtitleDto> cues) {
        double bestOverlap = 0;
        String best = null;
        for (SubtitleDto cue : cues) {
            if (cue.getText() == null || cue.getText().isBlank()) continue;
            double overlap = overlap(start, end, cue.getStartTime(), cue.getEndTime());
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = cue.getText().trim();
            }
        }
        return bestOverlap > 0 ? best : null;
    }

    private static double overlap(double aStart, double aEnd, double bStart, double bEnd) {
        double start = Math.max(aStart, bStart);
        double end = Math.min(aEnd, bEnd);
        return Math.max(0, end - start);
    }
}
