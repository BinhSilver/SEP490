package com.jlas.jlptpath.util;

import com.jlas.jlptpath.dto.JpathModeSuggestion;
import com.jlas.jlptpath.enums.JpathStudyMode;
import java.util.ArrayList;
import java.util.List;

/**
 * [JLAS-W0108] Combined/split suggestion (policy #27), checked in order: no Smart Test or fewer than 2 skill scores →
 * COMBINED (NO_SMART_TEST); m ≤ 30 → COMBINED (LOW_MINUTES); max − min of the vocab/kanji/grammar scores &lt; 15 →
 * COMBINED (EVEN_SKILLS); otherwise SPLIT (WEAK_SKILL) naming the lowest skill (first of vocab, kanji, grammar on a tie).
 */
public final class JpathModeRule {

    private static final int LOW_MINUTES = 30;
    private static final int EVEN_SPREAD = 15;

    private JpathModeRule() {
    }

    public static JpathModeSuggestion suggest(int m, Integer vocab, Integer kanji, Integer grammar,
                                              boolean hasSmartTest) {
        List<Score> scores = new ArrayList<>();
        add(scores, "Từ vựng", vocab);
        add(scores, "Kanji", kanji);
        add(scores, "Ngữ pháp", grammar);
        if (!hasSmartTest || scores.size() < 2) {
            return new JpathModeSuggestion(JpathStudyMode.COMBINED, "NO_SMART_TEST",
                    "Gợi ý Gộp vì bạn bắt đầu từ đầu");
        }
        if (m <= LOW_MINUTES) {
            return new JpathModeSuggestion(JpathStudyMode.COMBINED, "LOW_MINUTES",
                    "Gợi ý Gộp vì bạn rảnh " + m + " phút/ngày");
        }
        Score lowest = scores.get(0);
        int max = lowest.value();
        for (Score score : scores) {
            if (score.value() < lowest.value()) {
                lowest = score;
            }
            max = Math.max(max, score.value());
        }
        if (max - lowest.value() < EVEN_SPREAD) {
            return new JpathModeSuggestion(JpathStudyMode.COMBINED, "EVEN_SKILLS",
                    "Gợi ý Gộp vì điểm Từ vựng, Kanji, Ngữ pháp của bạn khá đều");
        }
        return new JpathModeSuggestion(JpathStudyMode.SPLIT, "WEAK_SKILL",
                "Gợi ý Tách vì " + lowest.name() + " thấp hơn hẳn, nên học riêng phần này");
    }

    private static void add(List<Score> scores, String name, Integer value) {
        if (value != null) {
            scores.add(new Score(name, value));
        }
    }

    private record Score(String name, int value) {
    }
}
