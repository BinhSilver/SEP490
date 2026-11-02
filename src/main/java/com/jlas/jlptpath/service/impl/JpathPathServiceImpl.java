package com.jlas.jlptpath.service.impl;

import com.jlas.assessment.entity.UserLearningSurvey;
import com.jlas.assessment.repository.UserLearningSurveyRepository;
import com.jlas.assessment.service.AssessmentService;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathConfirmationView;
import com.jlas.jlptpath.dto.JpathCreateRequest;
import com.jlas.jlptpath.dto.JpathDisplaySettings;
import com.jlas.jlptpath.dto.JpathEntryPreferenceRequest;
import com.jlas.jlptpath.dto.JpathEntryStatusView;
import com.jlas.jlptpath.dto.JpathEstimateRequest;
import com.jlas.jlptpath.dto.JpathLevelCell;
import com.jlas.jlptpath.dto.JpathLevelPreviewView;
import com.jlas.jlptpath.dto.JpathModeSuggestion;
import com.jlas.jlptpath.dto.JpathPaceView;
import com.jlas.jlptpath.dto.JpathPathView;
import com.jlas.jlptpath.dto.JpathPreviewItem;
import com.jlas.jlptpath.dto.JpathSliderBoundView;
import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.dto.JpathSliderView;
import com.jlas.jlptpath.dto.JpathSmartTestSnapshot;
import com.jlas.jlptpath.dto.JpathSmartTestSummary;
import com.jlas.jlptpath.dto.JpathTabCount;
import com.jlas.jlptpath.dto.JpathTabInfo;
import com.jlas.jlptpath.entity.JpathEntryPreference;
import com.jlas.jlptpath.entity.JpathSliderAdjustment;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserLevelId;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.entity.JpathUserSettings;
import com.jlas.jlptpath.enums.JpathEntrySource;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathKanaChoice;
import com.jlas.jlptpath.enums.JpathLevelCellState;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.enums.JpathSliderKind;
import com.jlas.jlptpath.enums.JpathStage;
import com.jlas.jlptpath.enums.JpathStudyMode;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathDailyTabRepository;
import com.jlas.jlptpath.repository.JpathEntryPreferenceRepository;
import com.jlas.jlptpath.repository.JpathSliderAdjustmentRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.repository.JpathUserSettingsRepository;
import com.jlas.jlptpath.service.JpathCalendarService;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathLevelInfo;
import com.jlas.jlptpath.service.JpathPathService;
import com.jlas.jlptpath.service.JpathSmartTestImporter;
import com.jlas.jlptpath.util.JpathDays;
import com.jlas.jlptpath.util.JpathModeRule;
import com.jlas.jlptpath.util.JpathOpenLevelRule;
import com.jlas.jlptpath.util.JpathPacingEngine;
import com.jlas.jlptpath.util.JpathSliderBounds;
import com.jlas.learning.dto.MockTestDestination;
import com.jlas.learning.service.MockTestDestinationProvider;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** [JLAS-W0108] Entry status, entry answers, the confirmation screen, pace estimates and the learner's active path. */
@Service
public class JpathPathServiceImpl implements JpathPathService {

    private static final List<String> DEFAULT_SKILL_DAYS = List.of("WED", "SAT");
    private static final String KANA_CHOICE_REQUIRED = "Hãy chọn bạn còn nhớ hay muốn ôn bảng chữ cái.";
    private static final List<String> LEVELS = List.of("N5", "N4", "N3", "N2", "N1");
    private static final JpathTab[] SLIDER_TABS = {JpathTab.VOCAB, JpathTab.KANJI, JpathTab.GRAMMAR};
    private static final int DEFAULT_MINUTES = 30;
    private static final String JLPT = "JLPT";
    private static final String SUBMITTED = "SUBMITTED";
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final int MAX_ADJUSTMENTS = 2;
    private static final int EXAM_MAX_YEARS = 3;
    private static final int PREVIEW_ITEMS = 5;
    private static final String FROM_ZERO = "FROM_ZERO";

    private final JpathEntryPreferenceRepository preferences;
    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository userLevels;
    private final JpathSliderAdjustmentRepository adjustments;
    private final JpathUserSettingsRepository settings;
    private final JpathDailyTabRepository dailyTabs;
    private final UserLearningSurveyRepository surveys;
    private final UserRepository users;
    private final JpathSmartTestReader smartTests;
    private final JpathCatalogService catalog;
    private final JpathTabCountsReader tabCounts;
    private final JpathPaceService paceService;
    private final JpathCalendarService calendar;
    private final JpathContentReader content;
    private final JpathClock clock;
    private final ObjectProvider<JpathSmartTestImporter> importer;
    private final AssessmentService assessment;
    private final MockTestDestinationProvider mockTests;

    public JpathPathServiceImpl(JpathEntryPreferenceRepository preferences, JpathUserPathRepository paths,
            JpathUserLevelRepository userLevels, JpathSliderAdjustmentRepository adjustments,
            JpathUserSettingsRepository settings, JpathDailyTabRepository dailyTabs,
            UserLearningSurveyRepository surveys, UserRepository users, JpathSmartTestReader smartTests,
            JpathCatalogService catalog, JpathTabCountsReader tabCounts, JpathPaceService paceService,
            JpathCalendarService calendar, JpathContentReader content, JpathClock clock,
            ObjectProvider<JpathSmartTestImporter> importer, AssessmentService assessment,
            MockTestDestinationProvider mockTests) {
        this.preferences = preferences;
        this.paths = paths;
        this.userLevels = userLevels;
        this.adjustments = adjustments;
        this.settings = settings;
        this.dailyTabs = dailyTabs;
        this.surveys = surveys;
        this.users = users;
        this.smartTests = smartTests;
        this.catalog = catalog;
        this.tabCounts = tabCounts;
        this.paceService = paceService;
        this.calendar = calendar;
        this.content = content;
        this.clock = clock;
        this.importer = importer;
        this.assessment = assessment;
        this.mockTests = mockTests;
    }

    @Override
    @Transactional(readOnly = true)
    public JpathEntryStatusView entryStatus(Long userId) {
        Optional<UserLearningSurvey> survey = surveys.findById(userId);
        String goal = survey.map(UserLearningSurvey::getGoal)
                .map(g -> g.trim().toUpperCase(Locale.ROOT)).orElse(null);
        Optional<JpathEntryPreference> preference = preferences.findById(userId);
        JpathEntryStatusView.SmartTest smartTest = smartTests.latestAttempt(userId)
                .map(a -> new JpathEntryStatusView.SmartTest(a.getId(), a.getStatus())).orElse(null);
        return new JpathEntryStatusView(
                survey.isPresent(),
                goal,
                preference.map(JpathEntryPreference::isStudiedBefore).orElse(null),
                preference.map(p -> p.getKanaChoice().name()).orElse(null),
                preference.isPresent(),
                preference.map(p -> JpathDays.codes(JpathDays.parse(p.getSkillDays()))).orElse(DEFAULT_SKILL_DAYS),
                preference.map(p -> JpathDays.codes(JpathDays.parse(p.getRestDays()))).orElse(List.of()),
                smartTest,
                pathStatus(userId));
    }

    @Override
    @Transactional
    public JpathEntryStatusView saveEntryPreference(Long userId, JpathEntryPreferenceRequest request) {
        boolean studied = Boolean.TRUE.equals(request.studiedBefore());
        JpathKanaChoice kana = studied ? studiedKanaChoice(request.kanaChoice()) : JpathKanaChoice.NONE;
        EnumSet<DayOfWeek> skillDays = JpathDays.parseCodes(request.skillDays());
        EnumSet<DayOfWeek> restDays = JpathDays.parseCodes(request.restDays());
        JpathDays.validate(skillDays, restDays);

        JpathEntryPreference preference = preferences.findById(userId)
                .orElseGet(() -> JpathEntryPreference.builder().userId(userId).build());
        preference.setStudiedBefore(studied);
        preference.setKanaChoice(kana);
        preference.setSkillDays(JpathDays.format(skillDays));
        preference.setRestDays(JpathDays.format(restDays));
        preferences.saveAndFlush(preference);
        return entryStatus(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public JpathConfirmationView confirmation(Long userId, JpathEntrySource source, Long assessmentAttemptId) {
        if (source == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Thiếu nguồn vào lộ trình.");
        }
        Optional<UserLearningSurvey> survey = surveys.findById(userId);
        Optional<JpathEntryPreference> preference = preferences.findById(userId);

        JpathSmartTestSnapshot snapshot = switch (source) {
            case SMART_TEST -> assessmentAttemptId != null ? smartTests.read(userId, assessmentAttemptId)
                    : smartTests.latest(userId).orElse(null);
            case ABANDONED_TEST -> assessmentAttemptId != null ? smartTests.read(userId, assessmentAttemptId) : null;
            case FROM_ZERO -> null;
        };
        if (snapshot != null && snapshot.goal() != null
                && !JLPT.equals(snapshot.goal().trim().toUpperCase(Locale.ROOT))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bài Smart Test này không dành cho mục tiêu JLPT.");
        }
        Long attemptId = snapshot == null ? null : snapshot.attemptId();
        boolean inProgress = source == JpathEntrySource.SMART_TEST && snapshot != null
                && IN_PROGRESS.equals(snapshot.status());
        // Preflight C9: the notebook preview runs only for a SUBMITTED attempt with a result.
        JpathSmartTestSummary summary = source == JpathEntrySource.SMART_TEST && snapshot != null
                && SUBMITTED.equals(snapshot.status()) && hasResult(snapshot) ? summary(userId, snapshot) : null;

        String wantedOpen = summary == null ? "N5" : JpathOpenLevelRule.openLevel(
                snapshot.abilityBand() != null ? snapshot.abilityBand() : snapshot.estimatedLevel());
        String openLevel = catalog.capToReleased(wantedOpen);
        String levelNote = wantedOpen.equals(openLevel) ? null
                : "Cấp " + wantedOpen + " sắp có. Lộ trình mở " + openLevel + ", cấp cao nhất hiện có.";
        String targetLevel = targetLevel(survey.map(UserLearningSurvey::getTargetLevel).orElse(null), openLevel);

        int minutes = nearestMinuteOption(survey.map(UserLearningSurvey::getDailyStudyMinutes).orElse(null));
        EnumSet<DayOfWeek> skillDays = preference.map(p -> JpathDays.parse(p.getSkillDays()))
                .orElseGet(() -> JpathDays.parseCodes(DEFAULT_SKILL_DAYS));
        EnumSet<DayOfWeek> restDays = preference.map(p -> JpathDays.parse(p.getRestDays()))
                .orElseGet(() -> EnumSet.noneOf(DayOfWeek.class));
        boolean kanaTab = kanaTab(preference, source);

        JpathModeSuggestion suggestion = summary == null
                ? JpathModeRule.suggest(minutes, null, null, null, false)
                : JpathModeRule.suggest(minutes, summary.vocabScore(), summary.kanjiScore(), summary.grammarScore(),
                        true);
        JpathSliderValues sliders = JpathPacingEngine.recommend(openLevel, minutes);
        LocalDate today = clock.today(userId);
        LocalDate surveyExam = survey.map(UserLearningSurvey::getExamDate).orElse(null);
        JpathPaceView pace = paceService.pace(userId, new JpathPaceService.Query(today, openLevel, targetLevel,
                surveyExam, minutes, skillDays, restDays, null, sliders, kanaTab ? JpathTabCountsReader.KANA_ROWS : 0,
                suggestion.mode(), null, true));

        return new JpathConfirmationView(source, attemptId, inProgress, summary, openLevel, levelNote, targetLevel,
                targetOptions(openLevel), JpathPaceService.usableExamDate(surveyExam, today), minutes,
                JpathPacingEngine.MINUTE_OPTIONS, JpathDays.codes(skillDays), JpathDays.codes(restDays), kanaTab,
                sliders, bounds(openLevel), pace, suggestion);
    }

    @Override
    @Transactional(readOnly = true)
    public JpathPaceView estimate(Long userId, JpathEstimateRequest request) {
        int minutes = request.dailyMinutes() == null ? -1 : request.dailyMinutes();
        JpathPacingEngine.requireMinuteOption(minutes);
        String openLevel = releasedLevel(request.openLevel());
        String targetLevel = request.targetLevel() == null ? openLevel : releasedLevel(request.targetLevel());
        if (LEVELS.indexOf(targetLevel) < LEVELS.indexOf(openLevel)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Cấp mục tiêu không được thấp hơn cấp mở.");
        }
        EnumSet<DayOfWeek> skillDays = JpathDays.parseCodes(request.skillDays());
        EnumSet<DayOfWeek> restDays = JpathDays.parseCodes(request.restDays());
        JpathDays.validate(skillDays, restDays);
        JpathSliderValues sliders = request.sliders();
        if (sliders == null) {
            sliders = JpathPacingEngine.recommend(openLevel, minutes);
        } else {
            JpathSliderBounds.requireInRange(openLevel, sliders);
        }
        JpathStudyMode mode = request.mode() == null ? JpathStudyMode.COMBINED : request.mode();
        // A learner without a path has every kana row left when the kana tab is on.
        int kanaRows = Boolean.TRUE.equals(request.kanaTab()) ? JpathTabCountsReader.KANA_ROWS : 0;
        return paceService.pace(userId, new JpathPaceService.Query(clock.today(userId), openLevel, targetLevel,
                request.examDate(), minutes, skillDays, restDays, null, sliders, kanaRows, mode, null, true));
    }

    private JpathSmartTestSummary summary(Long userId, JpathSmartTestSnapshot snapshot) {
        JpathSmartTestImporter wrongItems = importer.getIfAvailable();
        int notebook = wrongItems == null ? 0 : wrongItems.previewWrongItemCount(userId, snapshot.attemptId());
        return new JpathSmartTestSummary(snapshot.abilityBand(), snapshot.vocabScore(), snapshot.kanjiScore(),
                snapshot.grammarScore(), notebook, snapshot.partial());
    }

    /** A submitted attempt without a stored result (no level, band or score) counts as no Smart Test. */
    private static boolean hasResult(JpathSmartTestSnapshot s) {
        return s.abilityBand() != null || s.estimatedLevel() != null || s.vocabScore() != null
                || s.kanjiScore() != null || s.grammarScore() != null;
    }

    /** Survey target capped to the released levels, never below the open level; the open level when unusable. */
    private String targetLevel(String surveyTarget, String openLevel) {
        String wanted = surveyTarget == null ? "" : surveyTarget.trim().toUpperCase(Locale.ROOT);
        if (LEVELS.indexOf(wanted) <= LEVELS.indexOf(openLevel)) {
            return openLevel;
        }
        return catalog.capToReleased(wanted);
    }

    private List<String> targetOptions(String openLevel) {
        int open = LEVELS.indexOf(openLevel);
        return catalog.levels().stream()
                .filter(JpathLevelInfo::released)
                .map(JpathLevelInfo::level)
                .filter(level -> LEVELS.indexOf(level) >= open)
                .toList();
    }

    private static Map<JpathTab, JpathSliderBoundView> bounds(String level) {
        Map<JpathTab, JpathSliderBoundView> out = new EnumMap<>(JpathTab.class);
        for (JpathTab tab : SLIDER_TABS) {
            out.put(tab, new JpathSliderBoundView(JpathSliderBounds.min(level, tab),
                    JpathSliderBounds.max(level, tab)));
        }
        return Collections.unmodifiableMap(out);
    }

    /** Closest of {@link JpathPacingEngine#MINUTE_OPTIONS}; a tie goes to the smaller; no answer → 30. */
    private static int nearestMinuteOption(Integer minutes) {
        if (minutes == null) {
            return DEFAULT_MINUTES;
        }
        int best = JpathPacingEngine.MINUTE_OPTIONS.get(0);
        for (int option : JpathPacingEngine.MINUTE_OPTIONS) {
            if (Math.abs(option - minutes) < Math.abs(best - minutes)) {
                best = option;
            }
        }
        return best;
    }

    /** N1..N5 (trimmed, any case) that is released; VALIDATION_FAILED / JPATH_LEVEL_NOT_RELEASED otherwise. */
    private String releasedLevel(String level) {
        String key = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(key)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Cấp không hợp lệ.");
        }
        if (!catalog.isReleased(key)) {
            throw new ApiException(ErrorCode.JPATH_LEVEL_NOT_RELEASED);
        }
        return key;
    }

    @Override
    @Transactional
    public JpathPathView create(Long userId, JpathCreateRequest request) {
        // ---- checks, in order; nothing is written before all of them pass ----
        if (pathStatus(userId) != null) {
            throw new ApiException(ErrorCode.JPATH_ACTIVE_PATH_EXISTS);
        }
        UserLearningSurvey survey = surveys.findById(userId)
                .filter(s -> s.getGoal() != null && JLPT.equals(s.getGoal().trim().toUpperCase(Locale.ROOT)))
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Mục tiêu học hiện tại không phải JLPT. Hãy làm lại khảo sát."));
        Optional<JpathEntryPreference> preference = preferences.findById(userId);
        JpathEntrySource source = request.source();
        if (source == null || request.mode() == null || request.sliders() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Thiếu thông tin để tạo lộ trình.");
        }
        JpathSmartTestSnapshot snapshot = createSnapshot(userId, source, request.assessmentAttemptId(), survey,
                preference);
        // Task 17/18: a submitted attempt without a stored result counts as no Smart Test (no scores, no import).
        boolean withResult = source == JpathEntrySource.SMART_TEST && hasResult(snapshot);

        int minutes = request.dailyMinutes() == null ? -1 : request.dailyMinutes();
        JpathPacingEngine.requireMinuteOption(minutes);
        EnumSet<DayOfWeek> skillDays = JpathDays.parseCodes(request.skillDays());
        EnumSet<DayOfWeek> restDays = JpathDays.parseCodes(request.restDays());
        JpathDays.validate(skillDays, restDays);

        // The open level is decided here, never taken from the client's what-if estimate.
        String openLevel = catalog.capToReleased(withResult ? JpathOpenLevelRule.openLevel(
                snapshot.abilityBand() != null ? snapshot.abilityBand() : snapshot.estimatedLevel()) : "N5");
        String targetLevel = releasedLevel(request.targetLevel());
        if (LEVELS.indexOf(targetLevel) < LEVELS.indexOf(openLevel)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Cấp mục tiêu không được thấp hơn cấp mở "
                    + openLevel + ".");
        }
        JpathSliderValues sliders = request.sliders();
        JpathSliderBounds.requireInRange(openLevel, sliders);

        LocalDate today = clock.today(userId);
        LocalDate exam = request.examDate();
        if (exam != null && (!exam.isAfter(today) || exam.isAfter(today.plusYears(EXAM_MAX_YEARS)))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Ngày thi phải sau hôm nay và trong vòng 3 năm.");
        }

        boolean kanaTab = kanaTab(preference, source);
        JpathModeSuggestion suggestion = withResult
                ? JpathModeRule.suggest(minutes, snapshot.vocabScore(), snapshot.kanjiScore(),
                        snapshot.grammarScore(), true)
                : JpathModeRule.suggest(minutes, null, null, null, false);
        int kanaRowsLeft = kanaTab ? kanaRowsLeft(userId) : 0;
        JpathPaceView pace = paceService.pace(userId, new JpathPaceService.Query(today, openLevel, targetLevel, exam,
                minutes, skillDays, restDays, null, sliders, kanaRowsLeft, request.mode(), null, true));
        if (pace.blocked()) {
            throw new ApiException(ErrorCode.JPATH_TARGET_TOO_SOON, pace.messageVi());
        }

        // ---- writes ----
        JpathUserPath path = JpathUserPath.builder()
                .userId(userId)
                .status(JpathPathStatus.ACTIVE)
                .openLevel(openLevel)
                .targetLevel(targetLevel)
                .examDate(exam)
                .dailyMinutes(minutes)
                .skillDays(JpathDays.format(skillDays))
                .restDays(JpathDays.format(restDays))
                .studyMode(request.mode())
                .suggestedMode(suggestion.mode())
                .suggestionReason(suggestion.reason())
                .kanaTab(kanaTab)
                .entrySource(source)
                .assessmentAttemptId(snapshot == null ? null : snapshot.attemptId())
                .smartVocabScore(withResult ? score(snapshot.vocabScore()) : null)
                .smartKanjiScore(withResult ? score(snapshot.kanjiScore()) : null)
                .smartGrammarScore(withResult ? score(snapshot.grammarScore()) : null)
                .smartAbilityBand(withResult ? snapshot.abilityBand() : null)
                .studyDurationMonths(survey.getStudyDurationMonths())
                .build();
        try {
            // No old ACTIVE path can exist here (checked above), so nothing has to be flushed first; a parallel
            // request that inserted between the check and here trips uk_jpath_path_active.
            path = paths.saveAndFlush(path);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.JPATH_ACTIVE_PATH_EXISTS);
        }
        userLevels.save(JpathUserLevel.builder()
                .pathId(path.getId())
                .level(openLevel)
                .stage(JpathStage.STUDY)
                .sliderVocab(sliders.vocab())
                .sliderKanji(sliders.kanji())
                .sliderGrammar(sliders.grammar())
                .adjustmentsUsed(0)
                .firstDragUsed(false)
                .priorityMode(pace.skippedLowPriority() > 0)
                .openedAt(clock.now())
                .build());
        JpathSliderValues recommended = JpathPacingEngine.recommend(openLevel, minutes);
        adjustments.save(adjustment(path.getId(), openLevel, JpathSliderKind.INITIAL, null, recommended, minutes));
        if (!recommended.equals(sliders)) {
            adjustments.save(adjustment(path.getId(), openLevel, JpathSliderKind.CONFIRM, recommended, sliders,
                    minutes));
        }
        if (!settings.existsById(userId)) {
            settings.save(JpathUserSettings.builder().userId(userId).build());
        }
        if (withResult) {
            JpathSmartTestImporter wrongItems = importer.getIfAvailable();
            if (wrongItems != null) {
                wrongItems.importWrongItems(userId, snapshot.attemptId());
            }
        } else if (source == JpathEntrySource.ABANDONED_TEST && snapshot != null
                && IN_PROGRESS.equals(snapshot.status()) && smartTests.hasSession(snapshot.attemptId())) {
            assessment.finishSmartTest(userId, snapshot.attemptId());
        }
        adjustments.flush();
        return current(userId);
    }

    /**
     * The Smart Test behind a create request. SMART_TEST: the given attempt (else the newest), which must be submitted
     * (IN_PROGRESS → JPATH_SMART_TEST_IN_PROGRESS). ABANDONED_TEST: the given attempt, if any. FROM_ZERO: none, and
     * only for a learner who never studied (survey FROM_ZERO or entry answer "not studied"). A non-JLPT attempt →
     * VALIDATION_FAILED.
     */
    private JpathSmartTestSnapshot createSnapshot(Long userId, JpathEntrySource source, Long attemptId,
            UserLearningSurvey survey, Optional<JpathEntryPreference> preference) {
        JpathSmartTestSnapshot snapshot = switch (source) {
            case SMART_TEST -> {
                JpathSmartTestSnapshot s = attemptId != null ? smartTests.read(userId, attemptId)
                        : smartTests.latest(userId).orElseThrow(() -> new ApiException(
                                ErrorCode.VALIDATION_FAILED, "Bạn chưa có bài Smart Test."));
                if (IN_PROGRESS.equals(s.status())) {
                    throw new ApiException(ErrorCode.JPATH_SMART_TEST_IN_PROGRESS);
                }
                if (!SUBMITTED.equals(s.status())) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bài Smart Test này chưa được nộp.");
                }
                yield s;
            }
            case ABANDONED_TEST -> attemptId != null ? smartTests.read(userId, attemptId) : null;
            case FROM_ZERO -> {
                boolean neverStudied = FROM_ZERO.equals(survey.getSelfReportedLevel())
                        || preference.map(p -> !p.isStudiedBefore()).orElse(false);
                if (!neverStudied) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED,
                            "Bạn đã từng học tiếng Nhật, hãy bắt đầu bằng Smart Test.");
                }
                yield null;
            }
        };
        if (snapshot != null && snapshot.goal() != null
                && !JLPT.equals(snapshot.goal().trim().toUpperCase(Locale.ROOT))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bài Smart Test này không dành cho mục tiêu JLPT.");
        }
        return snapshot;
    }

    @Override
    @Transactional(readOnly = true)
    public JpathPathView current(Long userId) {
        JpathUserPath path = requireActive(userId);
        String open = path.getOpenLevel();
        JpathUserLevel level = levelRow(path);
        LocalDate today = clock.today(userId);
        Map<JpathTab, JpathTabCount> counts = tabCounts.read(userId, open);
        JpathTabCount kana = path.isKanaTab() ? tabCounts.kana(userId) : null;
        boolean kanaPhase = path.isKanaTab() && path.getKanaCompletedAt() == null;
        int kanaRowsLeft = kanaPhase ? (int) Math.max(0, kana.total() - kana.counted()) : 0;
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
                kanaRowsLeft, mode, skillPracticeSince, false));
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
                bounds(open), pace.estimatedMinutes(), path.getDailyMinutes(), pace.overBudget(),
                Math.max(0, MAX_ADJUSTMENTS - level.getAdjustmentsUsed()), level.isFirstDragUsed(), fromTomorrow);

        JpathUserSettings userSettings = settings.findById(userId).orElse(null);
        return new JpathPathView(path.getId(), displayName(userId), open, path.getTargetLevel(),
                JpathPaceService.usableExamDate(path.getExamDate(), today), !pace.late(), level.getStage(),
                levelCells(userId, open, counts), List.copyOf(tabs), calendar.week(userId, today, noNewLeft),
                pace.tabFinish(), pace.levelFinish(), sliderView, mode, path.getSuggestedMode(),
                path.getSuggestionReason(), path.isKanaTab(), path.getKanaCompletedAt() != null,
                JpathDays.codes(skillDays), JpathDays.codes(restDays),
                userSettings != null && userSettings.getTourDoneAt() != null,
                JpathDisplaySettings.from(userSettings, open), pace.warnings());
    }

    @Override
    @Transactional(readOnly = true)
    public JpathUserLevel openLevelState(Long userId) {
        JpathUserPath path = requireActive(userId);
        return userLevels.findById(new JpathUserLevelId(path.getId(), path.getOpenLevel()))
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
    }

    @Override
    @Transactional(readOnly = true)
    public JpathLevelPreviewView levelPreview(Long userId, String level) {
        String key = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(key)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Cấp không hợp lệ.");
        }
        Optional<JpathUserPath> path = findActive(userId);
        String open = path.map(JpathUserPath::getOpenLevel).orElse("N5");
        JpathLevelCellState state = cellState(key, open);
        JpathLevelInfo info = catalog.info(key);
        Map<JpathTab, Integer> counts = new EnumMap<>(JpathTab.class);
        counts.put(JpathTab.VOCAB, info.vocabCount());
        counts.put(JpathTab.KANJI, info.kanjiCount());
        counts.put(JpathTab.GRAMMAR, info.grammarCount());
        List<JpathPreviewItem> items = !catalog.isReleased(key) ? List.of() : content.visibleVocabulary(key)
                .stream().limit(PREVIEW_ITEMS)
                .map(v -> new JpathPreviewItem(v.getExpression(), v.getReading(), v.getMeaningVi()))
                .toList();
        String condition = switch (state) {
            case PASSED -> "Bạn đã vượt qua " + key + ".";
            case OPEN -> "Bạn đang học " + key + ".";
            case LOCKED -> "Đậu Thi thử JLPT " + open + " để mở " + key;
            case COMING_SOON -> "Cấp " + key + " sắp có.";
        };
        int currentPercent = path.isPresent() ? levelPercent(tabCounts.read(userId, open)) : 0;
        MockTestDestination destination = mockTests.resolveDestination(open);
        String mockUrl = destination != null && destination.available() ? destination.url() : null;
        return new JpathLevelPreviewView(key, state, Collections.unmodifiableMap(counts), items, condition,
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

    private int kanaRowsLeft(Long userId) {
        JpathTabCount kana = tabCounts.kana(userId);
        return (int) Math.max(0, kana.total() - kana.counted());
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

    private static JpathSliderAdjustment adjustment(Long pathId, String level, JpathSliderKind kind,
            JpathSliderValues before, JpathSliderValues after, int minutes) {
        return JpathSliderAdjustment.builder()
                .pathId(pathId)
                .level(level)
                .kind(kind)
                .counted(false)
                .vocabBefore(before == null ? null : before.vocab())
                .vocabAfter(after.vocab())
                .kanjiBefore(before == null ? null : before.kanji())
                .kanjiAfter(after.kanji())
                .grammarBefore(before == null ? null : before.grammar())
                .grammarAfter(after.grammar())
                .dailyMinutes(minutes)
                .build();
    }

    private static BigDecimal score(Integer value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(2);
    }

    /** Kana tab: on when the learner chose to review kana or never studied; without answers only for FROM_ZERO. */
    private static boolean kanaTab(Optional<JpathEntryPreference> preference, JpathEntrySource source) {
        return preference.map(p -> p.getKanaChoice() == JpathKanaChoice.REVIEW
                        || p.getKanaChoice() == JpathKanaChoice.NONE)
                .orElse(source == JpathEntrySource.FROM_ZERO);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JpathUserPath> findActive(Long userId) {
        return paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE);
    }

    @Override
    @Transactional(readOnly = true)
    public JpathUserPath requireActive(Long userId) {
        return findActive(userId).orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
    }

    private JpathPathStatus pathStatus(Long userId) {
        if (paths.existsByUserIdAndStatus(userId, JpathPathStatus.ACTIVE)) {
            return JpathPathStatus.ACTIVE;
        }
        return paths.existsByUserIdAndStatus(userId, JpathPathStatus.PAUSED) ? JpathPathStatus.PAUSED : null;
    }

    private static JpathKanaChoice studiedKanaChoice(String value) {
        if ("REMEMBER".equals(value)) {
            return JpathKanaChoice.REMEMBER;
        }
        if ("REVIEW".equals(value)) {
            return JpathKanaChoice.REVIEW;
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, KANA_CHOICE_REQUIRED);
    }
}
