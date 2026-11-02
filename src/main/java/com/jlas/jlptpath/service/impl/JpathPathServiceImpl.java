package com.jlas.jlptpath.service.impl;

import com.jlas.assessment.entity.UserLearningSurvey;
import com.jlas.assessment.repository.UserLearningSurveyRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathConfirmationView;
import com.jlas.jlptpath.dto.JpathEntryPreferenceRequest;
import com.jlas.jlptpath.dto.JpathEntryStatusView;
import com.jlas.jlptpath.dto.JpathEstimateRequest;
import com.jlas.jlptpath.dto.JpathModeSuggestion;
import com.jlas.jlptpath.dto.JpathPaceInput;
import com.jlas.jlptpath.dto.JpathPaceLevel;
import com.jlas.jlptpath.dto.JpathPaceResult;
import com.jlas.jlptpath.dto.JpathPaceView;
import com.jlas.jlptpath.dto.JpathSliderBoundView;
import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.dto.JpathSmartTestSnapshot;
import com.jlas.jlptpath.dto.JpathSmartTestSummary;
import com.jlas.jlptpath.dto.JpathTabCount;
import com.jlas.jlptpath.dto.JpathWarningView;
import com.jlas.jlptpath.entity.JpathEntryPreference;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathEntrySource;
import com.jlas.jlptpath.enums.JpathKanaChoice;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.enums.JpathStudyMode;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathEntryPreferenceRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
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
    private static final String EXAM_DATE_PASSED_VI = "Ngày thi đã qua. Bạn có thể đặt ngày thi mới trong Cài đặt.";

    private final JpathEntryPreferenceRepository preferences;
    private final JpathUserPathRepository paths;
    private final UserLearningSurveyRepository surveys;
    private final JpathSmartTestReader smartTests;
    private final JpathCatalogService catalog;
    private final JpathTabCountsReader tabCounts;
    private final JpathClock clock;
    private final ObjectProvider<JpathSmartTestImporter> importer;

    public JpathPathServiceImpl(JpathEntryPreferenceRepository preferences, JpathUserPathRepository paths,
            UserLearningSurveyRepository surveys, JpathSmartTestReader smartTests, JpathCatalogService catalog,
            JpathTabCountsReader tabCounts, JpathClock clock, ObjectProvider<JpathSmartTestImporter> importer) {
        this.preferences = preferences;
        this.paths = paths;
        this.surveys = surveys;
        this.smartTests = smartTests;
        this.catalog = catalog;
        this.tabCounts = tabCounts;
        this.clock = clock;
        this.importer = importer;
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
        boolean kanaTab = preference.map(p -> p.getKanaChoice() == JpathKanaChoice.REVIEW
                        || p.getKanaChoice() == JpathKanaChoice.NONE)
                .orElse(source == JpathEntrySource.FROM_ZERO);

        JpathModeSuggestion suggestion = summary == null
                ? JpathModeRule.suggest(minutes, null, null, null, false)
                : JpathModeRule.suggest(minutes, summary.vocabScore(), summary.kanjiScore(), summary.grammarScore(),
                        true);
        JpathSliderValues sliders = JpathPacingEngine.recommend(openLevel, minutes);
        LocalDate today = clock.today(userId);
        LocalDate surveyExam = survey.map(UserLearningSurvey::getExamDate).orElse(null);
        JpathPaceView pace = pace(userId, today, openLevel, targetLevel, surveyExam, minutes, skillDays, restDays,
                null, sliders, kanaTab ? JpathTabCountsReader.KANA_ROWS : 0, suggestion.mode());

        return new JpathConfirmationView(source, attemptId, inProgress, summary, openLevel, levelNote, targetLevel,
                targetOptions(openLevel), usableExamDate(surveyExam, today), minutes,
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
        return pace(userId, clock.today(userId), openLevel, targetLevel, request.examDate(), minutes, skillDays,
                restDays, null, sliders, kanaRows, mode);
    }

    /**
     * Pace estimate with the target enforced (policy #6). An exam date on or before today is not used and adds
     * EXAM_DATE_PASSED (#33). When the target is blocked, the estimate is redone with the recommended sliders (the ones
     * the block is decided on), so a blocked target is always late too.
     */
    private JpathPaceView pace(Long userId, LocalDate today, String openLevel, String targetLevel, LocalDate examDate,
            int minutes, Set<DayOfWeek> skillDays, Set<DayOfWeek> restDays, DayOfWeek thirdSkillDay,
            JpathSliderValues sliders, int kanaRowsLeft, JpathStudyMode mode) {
        LocalDate exam = usableExamDate(examDate, today);
        JpathPaceResult result = JpathPacingEngine.estimate(paceInput(userId, today, openLevel, targetLevel, exam,
                minutes, skillDays, restDays, thirdSkillDay, sliders, kanaRowsLeft, mode, true));
        JpathSliderValues recommended = JpathPacingEngine.recommend(openLevel, minutes);
        if (result.blocked() && !recommended.equals(sliders)) {
            result = JpathPacingEngine.estimate(paceInput(userId, today, openLevel, targetLevel, exam, minutes,
                    skillDays, restDays, thirdSkillDay, recommended, kanaRowsLeft, mode, true));
        }

        List<JpathWarningView> warnings = new ArrayList<>();
        if (examDate != null && exam == null) {
            warnings.add(new JpathWarningView("EXAM_DATE_PASSED", EXAM_DATE_PASSED_VI));
        }
        if (!result.blocked() && result.late()) {
            warnings.add(new JpathWarningView("LATE", "Với số mục mỗi ngày đang chọn, bạn chưa kịp xong "
                    + targetLevel + " trước ngày thi."));
        }
        if (result.overBudget()) {
            warnings.add(new JpathWarningView("OVER_BUDGET", "Ước tính " + result.minutesPerDay()
                    + " phút/ngày, nhiều hơn " + minutes + " phút bạn rảnh."));
        }
        if (!result.blocked() && result.skippedLowPriority() > 0) {
            warnings.add(new JpathWarningView("PRIORITY_MODE", "Sát ngày thi: lộ trình học mục hay gặp trước, "
                    + "bạn sẽ bỏ qua khoảng " + result.skippedLowPriority() + " mục ít gặp."));
        }
        return new JpathPaceView(result.tabFinish(), result.levelFinish(), result.targetFinish(), result.blocked(),
                result.late(), result.overBudget(), result.minutesPerDay(), minutes, result.suggestions(),
                result.skippedLowPriority(), result.messageVi(), List.copyOf(warnings));
    }

    /**
     * Engine input from the open level to the target: released levels use the learnable totals and the learner's
     * counted items; levels not released yet use the catalogue's planned totals (policy #5). The mode is never null.
     */
    private JpathPaceInput paceInput(Long userId, LocalDate today, String openLevel, String targetLevel,
            LocalDate examDate, int minutes, Set<DayOfWeek> skillDays, Set<DayOfWeek> restDays,
            DayOfWeek thirdSkillDay, JpathSliderValues sliders, int kanaRowsLeft, JpathStudyMode mode,
            boolean enforceTarget) {
        List<JpathPaceLevel> levels = new ArrayList<>();
        for (int i = LEVELS.indexOf(openLevel); i <= LEVELS.indexOf(targetLevel); i++) {
            String level = LEVELS.get(i);
            if (catalog.isReleased(level)) {
                Map<JpathTab, JpathTabCount> counts = tabCounts.read(userId, level);
                JpathTabCount v = counts.get(JpathTab.VOCAB);
                JpathTabCount k = counts.get(JpathTab.KANJI);
                JpathTabCount g = counts.get(JpathTab.GRAMMAR);
                levels.add(new JpathPaceLevel(level, (int) v.total(), (int) k.total(), (int) g.total(),
                        (int) v.counted(), (int) k.counted(), (int) g.counted(), null));
            } else {
                JpathLevelInfo info = catalog.info(level);
                levels.add(new JpathPaceLevel(level, info.vocabCount(), info.kanjiCount(), info.grammarCount(),
                        0, 0, 0, null));
            }
        }
        return new JpathPaceInput(today, levels, sliders, skillDays, restDays, thirdSkillDay, examDate, kanaRowsLeft,
                mode == null ? JpathStudyMode.COMBINED : mode, minutes, enforceTarget);
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

    /** Exam dates on or before today are not used (policy #33). */
    private static LocalDate usableExamDate(LocalDate examDate, LocalDate today) {
        return examDate != null && examDate.isAfter(today) ? examDate : null;
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
