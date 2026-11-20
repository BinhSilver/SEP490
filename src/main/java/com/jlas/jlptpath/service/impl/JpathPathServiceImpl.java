package com.jlas.jlptpath.service.impl;

import com.jlas.assessment.entity.UserLearningSurvey;
import com.jlas.assessment.repository.UserLearningSurveyRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathConfirmationView;
import com.jlas.jlptpath.dto.JpathCreateRequest;
import com.jlas.jlptpath.dto.JpathEntryPreferenceRequest;
import com.jlas.jlptpath.dto.JpathEntryStatusView;
import com.jlas.jlptpath.dto.JpathEstimateRequest;
import com.jlas.jlptpath.dto.JpathLevelPreviewView;
import com.jlas.jlptpath.dto.JpathModeSuggestion;
import com.jlas.jlptpath.dto.JpathPaceView;
import com.jlas.jlptpath.dto.JpathPathView;
import com.jlas.jlptpath.dto.JpathSliderUpdateRequest;
import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.dto.JpathSliderView;
import com.jlas.jlptpath.dto.JpathSmartTestSnapshot;
import com.jlas.jlptpath.dto.JpathSmartTestSummary;
import com.jlas.jlptpath.entity.JpathEntryPreference;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserLevelId;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathEntrySource;
import com.jlas.jlptpath.enums.JpathKanaChoice;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.enums.JpathStudyMode;
import com.jlas.jlptpath.repository.JpathEntryPreferenceRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathPathService;
import com.jlas.jlptpath.service.JpathSmartTestImporter;
import com.jlas.jlptpath.util.JpathDays;
import com.jlas.jlptpath.util.JpathModeRule;
import com.jlas.jlptpath.util.JpathPacingEngine;
import com.jlas.jlptpath.util.JpathSliderBounds;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.jlas.jlptpath.service.impl.JpathPathRules.IN_PROGRESS;
import static com.jlas.jlptpath.service.impl.JpathPathRules.LEVELS;

/**
 * [JLAS-W0108] Entry status, entry answers, the confirmation screen, pace estimates and the learner's active path.
 * Creating a path is done by {@link JpathPathCreator}, later changes (sliders, Rebalance, study mode, priority mode)
 * by {@link JpathPathUpdater}, pausing and resuming by {@link JpathPathChanger}, the path view and level previews by
 * {@link JpathPathViewAssembler}.
 */
@Service
public class JpathPathServiceImpl implements JpathPathService {

    private static final List<String> DEFAULT_SKILL_DAYS = List.of("WED", "SAT");
    private static final String KANA_CHOICE_REQUIRED = "Hãy chọn bạn còn nhớ hay muốn ôn bảng chữ cái.";
    private static final int DEFAULT_MINUTES = 30;

    private final JpathEntryPreferenceRepository preferences;
    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository userLevels;
    private final UserLearningSurveyRepository surveys;
    private final JpathSmartTestReader smartTests;
    private final JpathCatalogService catalog;
    private final JpathPaceService paceService;
    private final JpathClock clock;
    private final ObjectProvider<JpathSmartTestImporter> importer;
    private final JpathPathCreator creator;
    private final JpathPathViewAssembler views;
    private final JpathPathUpdater updater;
    private final JpathPathChanger changer;

    public JpathPathServiceImpl(JpathEntryPreferenceRepository preferences, JpathUserPathRepository paths,
            JpathUserLevelRepository userLevels, UserLearningSurveyRepository surveys,
            JpathSmartTestReader smartTests, JpathCatalogService catalog, JpathPaceService paceService,
            JpathClock clock, ObjectProvider<JpathSmartTestImporter> importer, JpathPathCreator creator,
            JpathPathViewAssembler views, JpathPathUpdater updater, JpathPathChanger changer) {
        this.preferences = preferences;
        this.paths = paths;
        this.userLevels = userLevels;
        this.surveys = surveys;
        this.smartTests = smartTests;
        this.catalog = catalog;
        this.paceService = paceService;
        this.clock = clock;
        this.importer = importer;
        this.creator = creator;
        this.views = views;
        this.updater = updater;
        this.changer = changer;
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
        JpathPathRules.requireJlptAttempt(snapshot);
        Long attemptId = snapshot == null ? null : snapshot.attemptId();
        boolean inProgress = source == JpathEntrySource.SMART_TEST && snapshot != null
                && IN_PROGRESS.equals(snapshot.status());
        // Preflight C9: the notebook preview runs only for a SUBMITTED attempt with a result.
        JpathSmartTestSummary summary = JpathPathRules.usableResult(source, snapshot) ? summary(userId, snapshot)
                : null;

        String wantedOpen = JpathPathRules.openLevelFor(summary == null ? null : snapshot);
        String openLevel = catalog.capToReleased(wantedOpen);
        String levelNote = wantedOpen.equals(openLevel) ? null
                : "Cấp " + wantedOpen + " sắp có. Lộ trình mở " + openLevel + ", cấp cao nhất hiện có.";
        String targetLevel = targetLevel(survey.map(UserLearningSurvey::getTargetLevel).orElse(null), openLevel);

        int minutes = nearestMinuteOption(survey.map(UserLearningSurvey::getDailyStudyMinutes).orElse(null));
        EnumSet<DayOfWeek> skillDays = preference.map(p -> JpathDays.parse(p.getSkillDays()))
                .orElseGet(() -> JpathDays.parseCodes(DEFAULT_SKILL_DAYS));
        EnumSet<DayOfWeek> restDays = preference.map(p -> JpathDays.parse(p.getRestDays()))
                .orElseGet(() -> EnumSet.noneOf(DayOfWeek.class));
        boolean kanaTab = JpathPathRules.kanaTab(preference, source);

        JpathModeSuggestion suggestion = summary == null
                ? JpathModeRule.suggest(minutes, null, null, null, false)
                : JpathModeRule.suggest(minutes, snapshot.modeVocab(), snapshot.modeKanji(),
                        snapshot.modeGrammar(), true);
        JpathSliderValues sliders = JpathPacingEngine.recommend(openLevel, minutes);
        LocalDate today = clock.today(userId);
        LocalDate surveyExam = survey.map(UserLearningSurvey::getExamDate).orElse(null);
        JpathPaceView pace = paceService.pace(userId, new JpathPaceService.Query(today, openLevel, targetLevel,
                surveyExam, minutes, skillDays, restDays, null, sliders, kanaTab ? JpathTabCountsReader.KANA_ROWS : 0,
                suggestion.mode(), null, true, null));

        return new JpathConfirmationView(source, attemptId, inProgress, summary, openLevel, levelNote, targetLevel,
                JpathPathRules.targetOptions(catalog, openLevel), JpathPaceService.usableExamDate(surveyExam, today),
                minutes, JpathPacingEngine.MINUTE_OPTIONS, JpathDays.codes(skillDays), JpathDays.codes(restDays),
                kanaTab, sliders, JpathPathRules.bounds(openLevel), pace, suggestion);
    }

    @Override
    @Transactional(readOnly = true)
    public JpathPaceView estimate(Long userId, JpathEstimateRequest request) {
        int minutes = request.dailyMinutes() == null ? -1 : request.dailyMinutes();
        JpathPacingEngine.requireMinuteOption(minutes);
        String openLevel = JpathPathRules.releasedLevel(catalog, request.openLevel());
        String targetLevel = request.targetLevel() == null ? openLevel
                : JpathPathRules.releasedLevel(catalog, request.targetLevel());
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
                request.examDate(), minutes, skillDays, restDays, null, sliders, kanaRows, mode, null, true, null));
    }

    @Override
    @Transactional
    public JpathPathView create(Long userId, JpathCreateRequest request) {
        return views.view(creator.create(userId, request));
    }

    @Override
    @Transactional(readOnly = true)
    public JpathPathView current(Long userId) {
        return views.view(requireActive(userId));
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
        return views.preview(userId, findActive(userId), key);
    }

    @Override
    @Transactional
    public JpathSliderView updateSliders(Long userId, JpathSliderUpdateRequest request) {
        JpathPathUpdater.Change c = updater.updateSliders(userId, request);
        return views.sliderView(c.path(), c.level(), c.pacing(), c.appliesFromTomorrow());
    }

    @Override
    @Transactional
    public JpathPaceView rebalance(Long userId) {
        return updater.rebalance(userId).pacing().pace();
    }

    @Override
    @Transactional
    public JpathPathView changeMode(Long userId, JpathStudyMode mode) {
        JpathPathUpdater.Change c = updater.changeMode(userId, mode);
        return views.view(c.path(), c.level(), c.pacing(), c.appliesFromTomorrow());
    }

    @Override
    @Transactional
    public void refreshPacing(Long userId) {
        updater.refreshPacing(userId);
    }

    @Override
    @Transactional
    public void pauseForGoalChange(Long userId) {
        changer.pauseForGoalChange(userId);
    }

    @Override
    @Transactional
    public JpathPathView resumePaused(Long userId) {
        JpathPathUpdater.Change c = changer.resumePaused(userId);
        return views.view(c.path(), c.level(), c.pacing(), c.appliesFromTomorrow());
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

    private JpathSmartTestSummary summary(Long userId, JpathSmartTestSnapshot snapshot) {
        JpathSmartTestImporter wrongItems = importer.getIfAvailable();
        int notebook = wrongItems == null ? 0 : wrongItems.previewWrongItemCount(userId, snapshot.attemptId());
        return new JpathSmartTestSummary(snapshot.abilityBand(), snapshot.estimatedLevel(), snapshot.vocabScore(), snapshot.kanjiScore(),
                snapshot.grammarScore(), notebook, snapshot.partial());
    }

    /** Survey target capped to the released levels, never below the open level; the open level when unusable. */
    private String targetLevel(String surveyTarget, String openLevel) {
        String wanted = surveyTarget == null ? "" : surveyTarget.trim().toUpperCase(Locale.ROOT);
        if (LEVELS.indexOf(wanted) <= LEVELS.indexOf(openLevel)) {
            return openLevel;
        }
        return catalog.capToReleased(wanted);
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
