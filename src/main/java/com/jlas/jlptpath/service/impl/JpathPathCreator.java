package com.jlas.jlptpath.service.impl;

import com.jlas.assessment.entity.UserLearningSurvey;
import com.jlas.assessment.repository.UserLearningSurveyRepository;
import com.jlas.assessment.service.AssessmentService;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathCreateRequest;
import com.jlas.jlptpath.dto.JpathModeSuggestion;
import com.jlas.jlptpath.dto.JpathPaceView;
import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.dto.JpathSmartTestSnapshot;
import com.jlas.jlptpath.dto.JpathTabCount;
import com.jlas.jlptpath.entity.JpathEntryPreference;
import com.jlas.jlptpath.entity.JpathSliderAdjustment;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.entity.JpathUserSettings;
import com.jlas.jlptpath.enums.JpathEntrySource;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.enums.JpathSliderKind;
import com.jlas.jlptpath.enums.JpathStage;
import com.jlas.jlptpath.repository.JpathEntryPreferenceRepository;
import com.jlas.jlptpath.repository.JpathSliderAdjustmentRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.repository.JpathUserSettingsRepository;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathSmartTestImporter;
import com.jlas.jlptpath.util.JpathDays;
import com.jlas.jlptpath.util.JpathModeRule;
import com.jlas.jlptpath.util.JpathPacingEngine;
import com.jlas.jlptpath.util.JpathSliderBounds;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.jlas.jlptpath.service.impl.JpathPathRules.IN_PROGRESS;
import static com.jlas.jlptpath.service.impl.JpathPathRules.JLPT;
import static com.jlas.jlptpath.service.impl.JpathPathRules.LEVELS;
import static com.jlas.jlptpath.service.impl.JpathPathRules.SUBMITTED;

/**
 * [JLAS-W0108] Checks a create request and writes the new path (see {@code JpathPathService.create}). Runs inside the
 * caller's transaction so the path, its level, the slider log, settings and the Smart Test import commit together.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpathPathCreator {

    static final String ACTIVE_PATH_KEY = "uk_jpath_path_active";
    private static final int EXAM_MAX_YEARS = 3;
    private static final String FROM_ZERO = "FROM_ZERO";

    private final JpathEntryPreferenceRepository preferences;
    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository userLevels;
    private final JpathSliderAdjustmentRepository adjustments;
    private final JpathUserSettingsRepository settings;
    private final UserLearningSurveyRepository surveys;
    private final JpathSmartTestReader smartTests;
    private final JpathCatalogService catalog;
    private final JpathTabCountsReader tabCounts;
    private final JpathPaceService paceService;
    private final JpathClock clock;
    private final ObjectProvider<JpathSmartTestImporter> importer;
    private final AssessmentService assessment;

    public JpathPathCreator(JpathEntryPreferenceRepository preferences, JpathUserPathRepository paths,
            JpathUserLevelRepository userLevels, JpathSliderAdjustmentRepository adjustments,
            JpathUserSettingsRepository settings, UserLearningSurveyRepository surveys,
            JpathSmartTestReader smartTests, JpathCatalogService catalog, JpathTabCountsReader tabCounts,
            JpathPaceService paceService, JpathClock clock, ObjectProvider<JpathSmartTestImporter> importer,
            AssessmentService assessment) {
        this.preferences = preferences;
        this.paths = paths;
        this.userLevels = userLevels;
        this.adjustments = adjustments;
        this.settings = settings;
        this.surveys = surveys;
        this.smartTests = smartTests;
        this.catalog = catalog;
        this.tabCounts = tabCounts;
        this.paceService = paceService;
        this.clock = clock;
        this.importer = importer;
        this.assessment = assessment;
    }

    /** Checks everything, then writes; returns the saved path. */
    public JpathUserPath create(Long userId, JpathCreateRequest request) {
        // ---- checks, in order; nothing is written before all of them pass ----
        if (paths.existsByUserIdAndStatus(userId, JpathPathStatus.ACTIVE)
                || paths.existsByUserIdAndStatus(userId, JpathPathStatus.PAUSED)) {
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
        JpathSmartTestSnapshot snapshot = snapshot(userId, source, request.assessmentAttemptId(), survey,
                preference);
        // Task 17/18: a submitted attempt without a stored result counts as no Smart Test (no scores, no import).
        boolean withResult = JpathPathRules.usableResult(source, snapshot);

        int minutes = request.dailyMinutes() == null ? -1 : request.dailyMinutes();
        JpathPacingEngine.requireMinuteOption(minutes);
        EnumSet<DayOfWeek> skillDays = JpathDays.parseCodes(request.skillDays());
        EnumSet<DayOfWeek> restDays = JpathDays.parseCodes(request.restDays());
        JpathDays.validate(skillDays, restDays);

        // The open level is decided here, never taken from the client's what-if estimate.
        String openLevel = catalog.capToReleased(JpathPathRules.openLevelFor(withResult ? snapshot : null));
        String targetLevel = JpathPathRules.releasedLevel(catalog, request.targetLevel());
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

        boolean kanaTab = JpathPathRules.kanaTab(preference, source);
        JpathModeSuggestion suggestion = withResult
                ? JpathModeRule.suggest(minutes, snapshot.vocabScore(), snapshot.kanjiScore(),
                        snapshot.grammarScore(), true)
                : JpathModeRule.suggest(minutes, null, null, null, false);
        int kanaRowsLeft = kanaTab ? kanaRowsLeft(userId) : 0;
        JpathPaceView pace = paceService.pace(userId, new JpathPaceService.Query(today, openLevel, targetLevel, exam,
                minutes, skillDays, restDays, null, sliders, kanaRowsLeft, request.mode(), null, true, null));
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
                // Every kana row already learned (e.g. on an earlier path): the kana phase is over from the start.
                .kanaCompletedAt(kanaTab && kanaRowsLeft == 0 ? clock.now() : null)
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
            if (isActivePathKey(e)) {
                throw new ApiException(ErrorCode.JPATH_ACTIVE_PATH_EXISTS);
            }
            throw e;
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
        return path;
    }

    /** Whether the most specific cause names the one-ACTIVE-path-per-learner key. */
    static boolean isActivePathKey(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(ACTIVE_PATH_KEY);
    }

    /**
     * The Smart Test behind a create request. SMART_TEST: the given attempt (else the newest), which must be submitted
     * (IN_PROGRESS → JPATH_SMART_TEST_IN_PROGRESS). ABANDONED_TEST: the given attempt, if any. FROM_ZERO: none, and
     * only for a learner who never studied (survey FROM_ZERO or entry answer "not studied"). A non-JLPT attempt →
     * VALIDATION_FAILED.
     */
    private JpathSmartTestSnapshot snapshot(Long userId, JpathEntrySource source, Long attemptId,
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
        JpathPathRules.requireJlptAttempt(snapshot);
        return snapshot;
    }

    private int kanaRowsLeft(Long userId) {
        JpathTabCount kana = tabCounts.kana(userId);
        return (int) Math.max(0, kana.total() - kana.counted());
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
}
