package com.jlas.jlptpath.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * [JLAS-W0108] Level summary after the mock test (Plan 3 Task 18): counts read from the learner's own rows of the level.
 * {@code accuracyPct} = micro-check correct / total of the level (null without a submitted micro-check);
 * {@code studyDays} = plans of the level with all_done_at; {@code weak} = up to 10 items with the highest forget_count.
 */
public record JpathLevelSummary(String level, int known, int learned, BigDecimal accuracyPct, int studyDays,
        List<WeakItem> weak, Integer mockScore, LocalDate passedOn, String nextLevel, boolean targetReached,
        boolean nextLevelComing, List<ExtraLink> extraLinks) {

    /** {@code label}: the word, kanji, pattern or kana as shown to the learner (the code when it is gone). */
    public record WeakItem(String type, String code, int forgetCount, String label) { }

    public record ExtraLink(String labelVi, String href) { }
}
