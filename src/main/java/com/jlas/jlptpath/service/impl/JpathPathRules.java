package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathSliderBoundView;
import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.dto.JpathSmartTestSnapshot;
import com.jlas.jlptpath.entity.JpathEntryPreference;
import com.jlas.jlptpath.entity.JpathSliderAdjustment;
import com.jlas.jlptpath.enums.JpathEntrySource;
import com.jlas.jlptpath.enums.JpathItemState;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathKanaChoice;
import com.jlas.jlptpath.enums.JpathSliderKind;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathLevelInfo;
import com.jlas.jlptpath.repository.JpathKanaRowRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.service.JpathTodayTargetsPort;
import com.jlas.jlptpath.util.JpathOpenLevelRule;
import com.jlas.jlptpath.util.JpathSliderBounds;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;

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
     * Wanted open level before capping to the released levels: the estimated overall level of a usable result (its
     * band's level when missing), N5 without one. Owner 20/11: the band never lifts it.
     */
    static String openLevelFor(JpathSmartTestSnapshot usableResult) {
        if (usableResult == null) {
            return "N5";
        }
        return JpathOpenLevelRule.openLevel(usableResult.estimatedLevel(), usableResult.abilityBand());
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

    /**
     * Re-applies the open level's sliders and mode to today's plan through the port (policy #29); returns the tabs
     * that keep today's targets, empty without the port (nothing refreshes today's plan then).
     */
    static Set<JpathTab> refreshToday(ObjectProvider<JpathTodayTargetsPort> todayTargets, Long userId) {
        JpathTodayTargetsPort port = todayTargets.getIfAvailable();
        if (port == null) {
            return EnumSet.noneOf(JpathTab.class);
        }
        Set<JpathTab> kept = port.refreshTodayTargets(userId);
        return kept == null ? EnumSet.noneOf(JpathTab.class) : kept;
    }

    /**
     * No learnable vocabulary, kanji or grammar item of {@code level} is left unstarted (the "no new items left" of
     * the calendar, shared by the path view and the daily plan).
     */
    static boolean noNewItemsLeft(JpathContentReader content, Long userId, String level, boolean priorityMode) {
        for (JpathTab tab : SLIDER_TABS) {
            JpathItemType type = JpathItemType.valueOf(tab.name());
            if (!content.nextNewCodes(userId, level, type, 1, priorityMode, null).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Source of the skill-day quiz (policy #25): a V/K/G tab, the passed kana rows, or none (no quiz). */
    enum SkillQuizSource {
        VOCAB, KANJI, GRAMMAR, KANA_ROW
    }

    /** LEARNED rows a V/K/G tab needs for the skill-day quiz (#25). */
    static final int SKILL_QUIZ_MIN_LEARNED = 10;
    /** Passed kana rows the KANA_ROW skill step needs, kana phase only (#25). */
    static final int SKILL_QUIZ_MIN_KANA_ROWS = 2;

    /**
     * Policy #25, the one rule for the skill-day quiz (plan Task 31/38; never the ALL source): the V/K/G tab with the
     * most LEARNED rows (learner rows not KNOWN) when it has at least 10 (ties VOCAB &gt; KANJI &gt; GRAMMAR); else,
     * in the kana phase with at least 2 passed rows, KANA_ROW; else empty (the day needs no quiz).
     */
    static Optional<SkillQuizSource> skillQuizSource(long vocabLearned, long kanjiLearned, long grammarLearned,
            boolean kanaPhase, long passedKanaRows) {
        long best = Math.max(vocabLearned, Math.max(kanjiLearned, grammarLearned));
        if (best >= SKILL_QUIZ_MIN_LEARNED) {
            if (vocabLearned == best) {
                return Optional.of(SkillQuizSource.VOCAB);
            }
            return Optional.of(kanjiLearned == best ? SkillQuizSource.KANJI : SkillQuizSource.GRAMMAR);
        }
        return kanaPhase && passedKanaRows >= SKILL_QUIZ_MIN_KANA_ROWS
                ? Optional.of(SkillQuizSource.KANA_ROW) : Optional.empty();
    }

    /** {@link #skillQuizSource} for the learner's rows of {@code level}. */
    static Optional<SkillQuizSource> skillQuizSource(JpathUserItemRepository userItems,
            JpathKanaRowRepository kanaRows, Long userId, String level, boolean kanaPhase) {
        long[] learned = new long[SLIDER_TABS.length];
        for (int i = 0; i < SLIDER_TABS.length; i++) {
            JpathItemType type = JpathItemType.valueOf(SLIDER_TABS[i].name());
            learned[i] = userItems.countByUserIdAndLevelAndItemTypeAndState(userId, level, type,
                    JpathItemState.LEARNING)
                    + userItems.countByUserIdAndLevelAndItemTypeAndState(userId, level, type,
                    JpathItemState.NOTEBOOK);
        }
        long rows = kanaPhase ? kanaRows.countCompletedRows(userId) : 0;
        return skillQuizSource(learned[0], learned[1], learned[2], kanaPhase, rows);
    }
}
