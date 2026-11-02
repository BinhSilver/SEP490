package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathSliderBoundView;
import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.dto.JpathSmartTestSnapshot;
import com.jlas.jlptpath.entity.JpathEntryPreference;
import com.jlas.jlptpath.entity.JpathSliderAdjustment;
import com.jlas.jlptpath.enums.JpathEntrySource;
import com.jlas.jlptpath.enums.JpathKanaChoice;
import com.jlas.jlptpath.enums.JpathSliderKind;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathLevelInfo;
import com.jlas.jlptpath.util.JpathOpenLevelRule;
import com.jlas.jlptpath.util.JpathSliderBounds;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** [JLAS-W0108] Rules shared by the confirmation screen, path creation and the path view. */
final class JpathPathRules {

    static final List<String> LEVELS = List.of("N5", "N4", "N3", "N2", "N1");
    static final JpathTab[] SLIDER_TABS = {JpathTab.VOCAB, JpathTab.KANJI, JpathTab.GRAMMAR};
    static final String JLPT = "JLPT";
    static final String SUBMITTED = "SUBMITTED";
    static final String IN_PROGRESS = "IN_PROGRESS";
    /** Counted slider adjustments per level (policy #29). */
    static final int MAX_ADJUSTMENTS = 2;

    private JpathPathRules() {
    }

    /** Kana tab: on when the learner chose to review kana or never studied; without answers only for FROM_ZERO. */
    static boolean kanaTab(Optional<JpathEntryPreference> preference, JpathEntrySource source) {
        return preference.map(p -> p.getKanaChoice() == JpathKanaChoice.REVIEW
                        || p.getKanaChoice() == JpathKanaChoice.NONE)
                .orElse(source == JpathEntrySource.FROM_ZERO);
    }

    /** Slider limits of {@code level} for the three slider tabs (policy #3). */
    static Map<JpathTab, JpathSliderBoundView> bounds(String level) {
        Map<JpathTab, JpathSliderBoundView> out = new EnumMap<>(JpathTab.class);
        for (JpathTab tab : SLIDER_TABS) {
            out.put(tab, new JpathSliderBoundView(JpathSliderBounds.min(level, tab),
                    JpathSliderBounds.max(level, tab)));
        }
        return Collections.unmodifiableMap(out);
    }

    /** A submitted attempt without a stored result (no level, band or score) counts as no Smart Test. */
    static boolean hasResult(JpathSmartTestSnapshot s) {
        return s != null && (s.abilityBand() != null || s.estimatedLevel() != null || s.vocabScore() != null
                || s.kanjiScore() != null || s.grammarScore() != null);
    }

    /** The SMART_TEST attempt counts as a Smart Test result: SUBMITTED and with a stored result. */
    static boolean usableResult(JpathEntrySource source, JpathSmartTestSnapshot s) {
        return source == JpathEntrySource.SMART_TEST && s != null && SUBMITTED.equals(s.status()) && hasResult(s);
    }

    /**
     * Wanted open level before capping to the released levels (policy #28): from the band (else the estimated level)
     * of a usable result, N5 without one.
     */
    static String openLevelFor(JpathSmartTestSnapshot usableResult) {
        if (usableResult == null) {
            return "N5";
        }
        return JpathOpenLevelRule.openLevel(usableResult.abilityBand() != null ? usableResult.abilityBand()
                : usableResult.estimatedLevel());
    }

    /** An attempt whose goal is set and not JLPT → VALIDATION_FAILED. */
    static void requireJlptAttempt(JpathSmartTestSnapshot s) {
        if (s != null && s.goal() != null && !JLPT.equals(s.goal().trim().toUpperCase(Locale.ROOT))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bài Smart Test này không dành cho mục tiêu JLPT.");
        }
    }

    /** N1..N5 (trimmed, any case) that is released; VALIDATION_FAILED / JPATH_LEVEL_NOT_RELEASED otherwise. */
    static String releasedLevel(JpathCatalogService catalog, String level) {
        String key = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(key)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Cấp không hợp lệ.");
        }
        if (!catalog.isReleased(key)) {
            throw new ApiException(ErrorCode.JPATH_LEVEL_NOT_RELEASED);
        }
        return key;
    }

    /** Released levels the target can be set to: the open level and the released levels above it. */
    static List<String> targetOptions(JpathCatalogService catalog, String openLevel) {
        int open = LEVELS.indexOf(openLevel);
        return catalog.levels().stream()
                .filter(JpathLevelInfo::released)
                .map(JpathLevelInfo::level)
                .filter(level -> LEVELS.indexOf(level) >= open)
                .toList();
    }

    /** Released levels below the open level (lower-level practice). */
    static List<String> lowerLevels(JpathCatalogService catalog, String openLevel) {
        int open = LEVELS.indexOf(openLevel);
        return catalog.levels().stream()
                .filter(JpathLevelInfo::released)
                .map(JpathLevelInfo::level)
                .filter(level -> LEVELS.indexOf(level) < open)
                .toList();
    }

    /** One slider log row; {@code before} null = no earlier value (INITIAL). */
    static JpathSliderAdjustment adjustment(Long pathId, String level, JpathSliderKind kind, boolean counted,
            JpathSliderValues before, JpathSliderValues after, int minutes) {
        return JpathSliderAdjustment.builder()
                .pathId(pathId)
                .level(level)
                .kind(kind)
                .counted(counted)
                .vocabBefore(before == null ? null : before.vocab())
                .vocabAfter(after.vocab())
                .kanjiBefore(before == null ? null : before.kanji())
                .kanjiAfter(after.kanji())
                .grammarBefore(before == null ? null : before.grammar())
                .grammarAfter(after.grammar())
                .dailyMinutes(minutes)
                .build();
    }
}
