package com.jlas.jlptpath.service.impl;

import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.jlptpath.dto.JpathDisplaySettings;
import com.jlas.jlptpath.dto.JpathLevelCell;
import com.jlas.jlptpath.dto.JpathLevelPreviewView;
import com.jlas.jlptpath.dto.JpathPaceView;
import com.jlas.jlptpath.dto.JpathPathView;
import com.jlas.jlptpath.dto.JpathPreviewItem;
import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.dto.JpathSliderView;
import com.jlas.jlptpath.dto.JpathTabCount;
import com.jlas.jlptpath.dto.JpathTabInfo;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserLevelId;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.entity.JpathUserSettings;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathLevelCellState;
import com.jlas.jlptpath.enums.JpathStage;
import com.jlas.jlptpath.enums.JpathStudyMode;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathDailyTabRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserSettingsRepository;
import com.jlas.jlptpath.service.JpathCalendarService;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathLevelInfo;
import com.jlas.jlptpath.util.JpathDays;
import com.jlas.jlptpath.util.JpathPacingEngine;
import com.jlas.learning.dto.MockTestDestination;
import com.jlas.learning.service.MockTestDestinationProvider;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import static com.jlas.jlptpath.service.impl.JpathPathRules.LEVELS;
import static com.jlas.jlptpath.service.impl.JpathPathRules.SLIDER_TABS;

/** [JLAS-W0108] Builds the path view and level previews; reads only. */
@Component
public class JpathPathViewAssembler {

    private static final int MAX_ADJUSTMENTS = 2;
    private static final int PREVIEW_ITEMS = 5;

    private final JpathUserLevelRepository userLevels;
    private final JpathUserSettingsRepository settings;
    private final JpathDailyTabRepository dailyTabs;
    private final UserRepository users;
    private final JpathCatalogService catalog;
    private final JpathTabCountsReader tabCounts;
    private final JpathPaceService paceService;
    private final JpathCalendarService calendar;
    private final JpathContentReader content;
    private final JpathClock clock;
    private final MockTestDestinationProvider mockTests;

    public JpathPathViewAssembler(JpathUserLevelRepository userLevels, JpathUserSettingsRepository settings,
            JpathDailyTabRepository dailyTabs, UserRepository users, JpathCatalogService catalog,
            JpathTabCountsReader tabCounts, JpathPaceService paceService, JpathCalendarService calendar,
            JpathContentReader content, JpathClock clock, MockTestDestinationProvider mockTests) {
        this.userLevels = userLevels;
        this.settings = settings;
        this.dailyTabs = dailyTabs;
        this.users = users;
        this.catalog = catalog;
        this.tabCounts = tabCounts;
        this.paceService = paceService;
        this.calendar = calendar;
        this.content = content;
        this.clock = clock;
        this.mockTests = mockTests;
    }

    /** View of {@code path} (the learner's active path). */
    @Transactional(readOnly = true)
    public JpathPathView view(JpathUserPath path) {
        Long userId = path.getUserId();
        String open = path.getOpenLevel();
        JpathUserLevel level = levelRow(path);
        LocalDate today = clock.today(userId);
        Map<JpathTab, JpathTabCount> counts = tabCounts.read(userId, open);
        JpathTabCount kana = path.isKanaTab() ? tabCounts.kana(userId) : null;
        int kanaRowsLeft = kana == null ? 0 : (int) Math.max(0, kana.total() - kana.counted());
        // No kana row left counts as completed even when kana_completed_at was never set.
        boolean kanaCompleted = path.getKanaCompletedAt() != null || kanaRowsLeft == 0;
        boolean kanaPhase = path.isKanaTab() && !kanaCompleted;
        JpathStudyMode mode = path.getStudyMode() == null ? JpathStudyMode.COMBINED : path.getStudyMode();
        EnumSet<DayOfWeek> skillDays = JpathDays.parse(path.getSkillDays());
        EnumSet<DayOfWeek> restDays = JpathDays.parse(path.getRestDays());
        JpathSliderValues sliders = new JpathSliderValues(level.getSliderVocab(), level.getSliderKanji(),
                level.getSliderGrammar());
        LocalDate skillPracticeSince = level.getSkillPracticeAt() == null ? null
                : level.getSkillPracticeAt().toLocalDate();

        // The path view shows the learner's own sliders: the target is not enforced, lateness is a warning.
        JpathPaceView pace = paceService.pace(userId, new JpathPaceService.Query(today, open, path.getTargetLevel(),
                path.getExamDate(), path.getDailyMinutes(), skillDays, restDays, storedThirdDay(path), sliders,
                kanaPhase ? kanaRowsLeft : 0, mode, skillPracticeSince, false, counts));
        // Task 14: in skill practice with no new item left every non-rest day is a skill day.
        boolean noNewLeft = level.getStage() == JpathStage.SKILL_PRACTICE
                && noNewItemsLeft(userId, open, level.isPriorityMode());

        List<JpathTabInfo> tabs = new ArrayList<>();
        if (kana != null) {
            tabs.add(new JpathTabInfo(JpathTab.KANA, true, false, kana.percent(), 0, 0, (int) kana.total()));
        }
        // Combined kana phase: vocabulary only, no new kanji or grammar yet (policy #26).
        boolean kanjiGrammarOpen = !(kanaPhase && mode == JpathStudyMode.COMBINED);
        for (JpathTab tab : SLIDER_TABS) {
            JpathTabCount c = counts.get(tab);
            tabs.add(new JpathTabInfo(tab, tab == JpathTab.VOCAB || kanjiGrammarOpen, false, c.percent(),
                    (int) c.known(), (int) c.notebook(), (int) c.total()));
        }
        tabs.add(new JpathTabInfo(JpathTab.SKILL, false, true, 0, 0, 0, 0));

        Set<JpathTab> started = EnumSet.noneOf(JpathTab.class);
        started.addAll(dailyTabs.findStartedTabs(userId, today));
        List<JpathTab> fromTomorrow = Arrays.stream(SLIDER_TABS).filter(started::contains).toList();
        JpathSliderView sliderView = new JpathSliderView(sliders.vocab(), sliders.kanji(), sliders.grammar(),
                JpathPathRules.bounds(open), pace.estimatedMinutes(), path.getDailyMinutes(), pace.overBudget(),
                Math.max(0, MAX_ADJUSTMENTS - level.getAdjustmentsUsed()), level.isFirstDragUsed(), fromTomorrow);

        JpathUserSettings userSettings = settings.findById(userId).orElse(null);
        return new JpathPathView(path.getId(), displayName(userId), open, path.getTargetLevel(),
                JpathPaceService.usableExamDate(path.getExamDate(), today), !pace.late(), level.getStage(),
                levelCells(userId, open, counts), List.copyOf(tabs), calendar.week(userId, today, noNewLeft),
                pace.tabFinish(), pace.levelFinish(), sliderView, mode, path.getSuggestedMode(),
                path.getSuggestionReason(), path.isKanaTab(), path.isKanaTab() && kanaCompleted,
                JpathDays.codes(skillDays), JpathDays.codes(restDays),
                userSettings != null && userSettings.getTourDoneAt() != null,
                JpathDisplaySettings.from(userSettings, open), pace.warnings());
    }

    /** Preview of {@code level} (already N1..N5) seen from the path's open level (N5 without a path). */
    @Transactional(readOnly = true)
    public JpathLevelPreviewView preview(Long userId, Optional<JpathUserPath> path, String level) {
        String open = path.map(JpathUserPath::getOpenLevel).orElse("N5");
        JpathLevelCellState state = cellState(level, open);
        JpathLevelInfo info = catalog.info(level);
        Map<JpathTab, Integer> counts = new EnumMap<>(JpathTab.class);
        counts.put(JpathTab.VOCAB, info.vocabCount());
        counts.put(JpathTab.KANJI, info.kanjiCount());
        counts.put(JpathTab.GRAMMAR, info.grammarCount());
        List<JpathPreviewItem> items = !catalog.isReleased(level) ? List.of() : content.visibleVocabulary(level)
                .stream().limit(PREVIEW_ITEMS)
                .map(v -> new JpathPreviewItem(v.getExpression(), v.getReading(), v.getMeaningVi()))
                .toList();
        String condition = switch (state) {
            case PASSED -> "Bạn đã vượt qua " + level + ".";
            case OPEN -> "Bạn đang học " + level + ".";
            case LOCKED -> "Đậu Thi thử JLPT " + open + " để mở " + level;
            case COMING_SOON -> "Cấp " + level + " sắp có.";
        };
        int currentPercent = path.isPresent() ? levelPercent(tabCounts.read(userId, open)) : 0;
        MockTestDestination destination = mockTests.resolveDestination(open);
        String mockUrl = destination != null && destination.available() ? destination.url() : null;
        return new JpathLevelPreviewView(level, state, Collections.unmodifiableMap(counts), items, condition,
                currentPercent, mockUrl);
    }

    /** Level bar N5→N1 (policy #16, preflight C36: percent = level %, learned/total = vocabulary). */
    private List<JpathLevelCell> levelCells(Long userId, String open, Map<JpathTab, JpathTabCount> openCounts) {
        List<JpathLevelCell> cells = new ArrayList<>(LEVELS.size());
        for (String level : LEVELS) {
            JpathLevelCellState state = cellState(level, open);
            switch (state) {
                case OPEN -> cells.add(cell(level, state, openCounts, "Đang học"));
                case PASSED -> cells.add(catalog.isReleased(level)
                        ? cell(level, state, tabCounts.read(userId, level), "Đã vượt qua")
                        : new JpathLevelCell(level, state, 0, catalog.info(level).vocabCount(), 0, "Đã vượt qua"));
                case LOCKED -> cells.add(new JpathLevelCell(level, state, 0, catalog.info(level).vocabCount(), 0,
                        "Xem trước · Thi thử " + open + " để mở"));
                case COMING_SOON -> cells.add(new JpathLevelCell(level, state, 0, catalog.info(level).vocabCount(),
                        0, "Sắp có"));
            }
        }
        return List.copyOf(cells);
    }

    private static JpathLevelCell cell(String level, JpathLevelCellState state, Map<JpathTab, JpathTabCount> counts,
            String note) {
        JpathTabCount vocab = counts.get(JpathTab.VOCAB);
        return new JpathLevelCell(level, state, (int) vocab.counted(), (int) vocab.total(), levelPercent(counts),
                note);
    }

    /** Below the open level PASSED, the open level OPEN, above it LOCKED when released, else COMING_SOON. */
    private JpathLevelCellState cellState(String level, String open) {
        int diff = LEVELS.indexOf(level) - LEVELS.indexOf(open);
        if (diff < 0) {
            return JpathLevelCellState.PASSED;
        }
        if (diff == 0) {
            return JpathLevelCellState.OPEN;
        }
        return catalog.isReleased(level) ? JpathLevelCellState.LOCKED : JpathLevelCellState.COMING_SOON;
    }

    /** Level % = mean of the vocabulary, kanji and grammar tab % (policy #16), rounded down. */
    private static int levelPercent(Map<JpathTab, JpathTabCount> counts) {
        int sum = 0;
        for (JpathTab tab : SLIDER_TABS) {
            sum += counts.get(tab).percent();
        }
        return sum / SLIDER_TABS.length;
    }

    /** The open level row; a missing row (should not happen) reads as STUDY with the recommended sliders. */
    private JpathUserLevel levelRow(JpathUserPath path) {
        return userLevels.findById(new JpathUserLevelId(path.getId(), path.getOpenLevel())).orElseGet(() -> {
            JpathSliderValues r = JpathPacingEngine.recommend(path.getOpenLevel(), path.getDailyMinutes());
            return JpathUserLevel.builder().pathId(path.getId()).level(path.getOpenLevel())
                    .sliderVocab(r.vocab()).sliderKanji(r.kanji()).sliderGrammar(r.grammar()).build();
        });
    }

    /** No learnable vocabulary, kanji or grammar item of {@code level} is left unstarted. */
    private boolean noNewItemsLeft(Long userId, String level, boolean priorityMode) {
        for (JpathTab tab : SLIDER_TABS) {
            JpathItemType type = JpathItemType.valueOf(tab.name());
            if (!content.nextNewCodes(userId, level, type, 1, priorityMode, null).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** The stored third skill day when it is one valid code, else null (automatic choice). */
    private static DayOfWeek storedThirdDay(JpathUserPath path) {
        try {
            Set<DayOfWeek> days = JpathDays.parse(path.getThirdSkillDay());
            return days.size() == 1 ? days.iterator().next() : null;
        } catch (ApiException e) {
            return null;
        }
    }

    private String displayName(Long userId) {
        return users.findById(userId).map(u -> {
            if (u.getFullName() != null && !u.getFullName().isBlank()) {
                return u.getFullName().trim();
            }
            return u.getUsername() != null && !u.getUsername().isBlank() ? u.getUsername().trim() : null;
        }).orElse(null);
    }
}
