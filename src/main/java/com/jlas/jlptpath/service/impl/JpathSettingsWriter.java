package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.config.JpathProperties;
import com.jlas.jlptpath.dto.JpathModeSuggestion;
import com.jlas.jlptpath.dto.JpathSettingsUpdateRequest;
import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.entity.JpathUserSettings;
import com.jlas.jlptpath.enums.JpathEntrySource;
import com.jlas.jlptpath.enums.JpathSliderKind;
import com.jlas.jlptpath.enums.JpathStudyMode;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathSliderAdjustmentRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.repository.JpathUserSettingsRepository;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathTodayTargetsPort;
import com.jlas.jlptpath.util.JpathDays;
import com.jlas.jlptpath.util.JpathModeRule;
import com.jlas.jlptpath.util.JpathPacingEngine;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.jlas.jlptpath.service.impl.JpathPathRules.LEVELS;

/**
 * [JLAS-W0108] Writes a settings change (see {@code JpathSettingsService.update}); runs inside the caller's
 * transaction. Every check runs before the first write. Schedule changes follow the lock order of
 * {@link JpathPathUpdater}: the ACTIVE path is locked first ({@link JpathUserPathRepository#findActiveForUpdate}, the
 * first load of the path in the transaction), then its open level row, then anything is written.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpathSettingsWriter {

    private static final int EXAM_MAX_YEARS = 3;
    private static final List<String> ROMAJI_MODES = List.of("AUTO", "ON", "OFF");
    private static final List<String> FONT_SIZES = List.of("S", "M", "L");
    private static final List<String> LIST_VIEWS = List.of("TOPICS", "FLAT");

    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository userLevels;
    private final JpathSliderAdjustmentRepository adjustments;
    private final JpathUserSettingsRepository settings;
    private final JpathCatalogService catalog;
    private final JpathPathViewAssembler views;
    private final JpathClock clock;
    private final JpathProperties props;
    private final ObjectProvider<JpathTodayTargetsPort> todayTargets;

    public JpathSettingsWriter(JpathUserPathRepository paths, JpathUserLevelRepository userLevels,
            JpathSliderAdjustmentRepository adjustments, JpathUserSettingsRepository settings,
            JpathCatalogService catalog, JpathPathViewAssembler views, JpathClock clock, JpathProperties props,
            ObjectProvider<JpathTodayTargetsPort> todayTargets) {
        this.paths = paths;
        this.userLevels = userLevels;
        this.adjustments = adjustments;
        this.settings = settings;
        this.catalog = catalog;
        this.views = views;
        this.clock = clock;
        this.props = props;
        this.todayTargets = todayTargets;
    }

    /**
     * Result of a change. Schedule changed: the locked path and open level row and the pace after the change
     * ({@code appliesFromTomorrow} = tabs of today's plan that keep their targets, null when the sliders and mode did
     * not change); otherwise those three are null. {@code settings} = the saved row, null when no display field was
     * given.
     */
    public record Result(JpathUserPath path, JpathUserLevel level, JpathPathViewAssembler.Pacing pacing,
            Set<JpathTab> appliesFromTomorrow, JpathUserSettings settings) {
    }

    /** Checks the whole request, then writes. */
    public Result update(Long userId, JpathSettingsUpdateRequest r) {
        if (r == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Thiếu nội dung cài đặt.");
        }
        // ---- checks of the display block (no database access) ----
        String romaji = oneOf(r.romajiMode(), ROMAJI_MODES, "Chế độ romaji không hợp lệ.");
        String font = oneOf(r.fontSize(), FONT_SIZES, "Cỡ chữ không hợp lệ.");
        String listView = oneOf(r.vocabListView(), LIST_VIEWS, "Kiểu danh sách từ vựng không hợp lệ.");
        LocalTime reminderTime = r.reminderTime() == null ? null : reminderTime(r.reminderTime());

        // ---- schedule: lock, check, then write ----
        ScheduleChange change = r.changesSchedule() ? checkSchedule(userId, r) : null;
        Set<JpathTab> fromTomorrow = change == null ? null : writeSchedule(change);

        JpathUserSettings row = null;
        if (changesDisplay(r)) {
            row = settings.findById(userId).orElseGet(() -> JpathUserSettings.builder().userId(userId)
                    .reminderTime(props.reminder().defaultLocalTime()).build());
            if (r.furiganaHiddenAll() != null) {
                row.setFuriganaHiddenAll(r.furiganaHiddenAll());
            }
            if (romaji != null) {
                row.setRomajiMode(romaji);
            }
            if (font != null) {
                row.setFontSize(font);
            }
            if (r.soundOn() != null) {
                row.setSoundOn(r.soundOn());
            }
            if (r.autoplayAudio() != null) {
                row.setAutoplayAudio(r.autoplayAudio());
            }
            if (r.quizTimerOn() != null) {
                row.setQuizTimerOn(r.quizTimerOn());
            }
            if (r.grammarTimerOn() != null) {
                row.setGrammarTimerOn(r.grammarTimerOn());
            }
            if (r.reminderWeb() != null) {
                row.setReminderWeb(r.reminderWeb());
            }
            if (r.reminderEmail() != null) {
                row.setReminderEmail(r.reminderEmail());
            }
            if (reminderTime != null) {
                row.setReminderTime(reminderTime);
            }
            if (listView != null) {
                row.setVocabListView(listView);
            }
            if (Boolean.TRUE.equals(r.tourDone()) && row.getTourDoneAt() == null) {
                row.setTourDoneAt(clock.now());
            }
            if (Boolean.TRUE.equals(r.swipeHintDone()) && row.getSwipeHintDoneAt() == null) {
                row.setSwipeHintDoneAt(clock.now());
            }
            row = settings.saveAndFlush(row);
        }
        return change == null ? new Result(null, null, null, null, row)
                : new Result(change.path, change.level, change.pacing, fromTomorrow, row);
    }

    /** Checked new values of the schedule with the pace they give (the target enforced when it changed). */
    private record ScheduleChange(JpathUserPath path, JpathUserLevel level, JpathPathViewAssembler.Schedule schedule,
            JpathPathViewAssembler.Pacing pacing, boolean targetChanged, boolean modeChanged,
            boolean minutesChanged) {
    }

    private ScheduleChange checkSchedule(Long userId, JpathSettingsUpdateRequest r) {
        JpathUserPath path = paths.findActiveForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
        JpathUserLevel level = userLevels.findForUpdate(path.getId(), path.getOpenLevel())
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
        String open = path.getOpenLevel();

        String target = path.getTargetLevel();
        if (r.targetLevel() != null) {
            target = catalog.capToReleased(r.targetLevel());
            if (LEVELS.indexOf(target) < LEVELS.indexOf(open)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Cấp mục tiêu không được thấp hơn cấp mở "
                        + open + ".");
            }
        }

        LocalDate exam = path.getExamDate();
        if (Boolean.TRUE.equals(r.clearExamDate())) {
            if (r.examDate() != null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Hãy chọn ngày thi mới hoặc xóa ngày thi.");
            }
            exam = null;
        } else if (r.examDate() != null) {
            LocalDate today = clock.today(userId);
            if (!r.examDate().isAfter(today) || r.examDate().isAfter(today.plusYears(EXAM_MAX_YEARS))) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Ngày thi phải sau hôm nay và trong vòng 3 năm.");
            }
            exam = r.examDate();
        }

        int minutes = path.getDailyMinutes();
        if (r.dailyMinutes() != null) {
            minutes = r.dailyMinutes();
            JpathPacingEngine.requireMinuteOption(minutes);
        }

        EnumSet<DayOfWeek> skillDays = r.skillDays() != null ? JpathDays.parseCodes(r.skillDays())
                : JpathDays.parse(path.getSkillDays());
        EnumSet<DayOfWeek> restDays = r.restDays() != null ? JpathDays.parseCodes(r.restDays())
                : JpathDays.parse(path.getRestDays());
        JpathDays.validate(skillDays, restDays);

        DayOfWeek third;
        if (r.thirdSkillDay() != null) {
            third = r.thirdSkillDay().isBlank() ? null : oneDay(r.thirdSkillDay());
            if (third != null && (skillDays.contains(third) || restDays.contains(third))) {
                throw new ApiException(ErrorCode.JPATH_SKILL_DAYS_INVALID,
                        "Ngày kỹ năng thứ 3 không được trùng ngày kỹ năng hay ngày nghỉ.");
            }
        } else {
            // The stored day is dropped when the new skill or rest days take it.
            third = JpathPathViewAssembler.storedThirdDay(path);
            if (third != null && (skillDays.contains(third) || restDays.contains(third))) {
                third = null;
            }
        }

        JpathStudyMode storedMode = path.getStudyMode() == null ? JpathStudyMode.COMBINED : path.getStudyMode();
        JpathStudyMode mode = r.studyMode() == null ? storedMode : r.studyMode();

        boolean targetChanged = !target.equals(path.getTargetLevel()) || !Objects.equals(exam, path.getExamDate());
        // Policy #29: a new target or exam resets the sliders to the recommendation for the minutes.
        JpathSliderValues sliders = targetChanged ? JpathPacingEngine.recommend(open, minutes)
                : new JpathSliderValues(level.getSliderVocab(), level.getSliderKanji(), level.getSliderGrammar());
        JpathPathViewAssembler.Schedule schedule = new JpathPathViewAssembler.Schedule(target, exam, minutes,
                skillDays, restDays, third, mode, sliders);
        // Enforcing only decides `blocked`; when not blocked the pace equals the learner view of the new schedule.
        JpathPathViewAssembler.Pacing pacing = views.pacing(path, level, schedule, targetChanged);
        if (pacing.pace().blocked()) {
            throw new ApiException(ErrorCode.JPATH_TARGET_TOO_SOON, pacing.pace().messageVi());
        }
        return new ScheduleChange(path, level, schedule, pacing, targetChanged, mode != storedMode,
                minutes != path.getDailyMinutes());
    }

    /** Writes a checked schedule; returns the tabs of today's plan that keep their targets (null = not refreshed). */
    private Set<JpathTab> writeSchedule(ScheduleChange c) {
        JpathUserPath path = c.path;
        JpathUserLevel level = c.level;
        JpathPathViewAssembler.Schedule s = c.schedule;
        JpathSliderValues before = new JpathSliderValues(level.getSliderVocab(), level.getSliderKanji(),
                level.getSliderGrammar());

        path.setTargetLevel(s.targetLevel());
        path.setExamDate(s.examDate());
        path.setDailyMinutes(s.minutes());
        path.setSkillDays(JpathDays.format(s.skillDays()));
        path.setRestDays(JpathDays.format(s.restDays()));
        path.setThirdSkillDay(s.thirdSkillDay() == null ? null : JpathDays.format(List.of(s.thirdSkillDay())));
        // Same effect as JpathPathUpdater.changeMode: the mode is stored, sliders kept, today's plan refreshed below.
        path.setStudyMode(s.mode());
        if (c.minutesChanged) {
            // Policy #27 depends on the minutes: keep the stored suggestion in step with them.
            JpathModeSuggestion suggestion = suggestion(path);
            path.setSuggestedMode(suggestion.mode());
            path.setSuggestionReason(suggestion.reason());
        }
        boolean slidersChanged = false;
        if (c.targetChanged) {
            JpathSliderValues after = s.sliders();
            slidersChanged = !after.equals(before);
            level.setSliderVocab(after.vocab());
            level.setSliderKanji(after.kanji());
            level.setSliderGrammar(after.grammar());
            adjustments.save(JpathPathRules.adjustment(path.getId(), path.getOpenLevel(),
                    JpathSliderKind.TARGET_CHANGE, false, before, after, s.minutes()));
        }
        // Priority mode (#30) from the pace of the new schedule, as JpathPathUpdater does.
        level.setPriorityMode(c.pacing.pace().skippedLowPriority() > 0);
        paths.flush();
        return slidersChanged || c.modeChanged ? refreshToday(path.getUserId()) : null;
    }

    /**
     * Suggestion of policy #27 for the path's current minutes, deciding "has a Smart Test" as {@link JpathPathCreator}
     * does: a SMART_TEST path whose attempt had a stored result (band or a skill score, stored only then).
     */
    public static JpathModeSuggestion suggestion(JpathUserPath path) {
        Integer vocab = score(path.getSmartVocabScore());
        Integer kanji = score(path.getSmartKanjiScore());
        Integer grammar = score(path.getSmartGrammarScore());
        boolean hasSmartTest = path.getEntrySource() == JpathEntrySource.SMART_TEST
                && path.getAssessmentAttemptId() != null
                && (path.getSmartAbilityBand() != null || vocab != null || kanji != null || grammar != null);
        return hasSmartTest ? JpathModeRule.suggest(path.getDailyMinutes(), vocab, kanji, grammar, true)
                : JpathModeRule.suggest(path.getDailyMinutes(), null, null, null, false);
    }

    private static Integer score(BigDecimal value) {
        return value == null ? null : value.intValue();
    }

    private Set<JpathTab> refreshToday(Long userId) {
        JpathTodayTargetsPort port = todayTargets.getIfAvailable();
        if (port == null) {
            return EnumSet.noneOf(JpathTab.class);
        }
        Set<JpathTab> kept = port.refreshTodayTargets(userId);
        return kept == null ? EnumSet.noneOf(JpathTab.class) : kept;
    }

    private static boolean changesDisplay(JpathSettingsUpdateRequest r) {
        return r.furiganaHiddenAll() != null || r.romajiMode() != null || r.fontSize() != null || r.soundOn() != null
                || r.autoplayAudio() != null || r.quizTimerOn() != null || r.grammarTimerOn() != null
                || r.reminderWeb() != null || r.reminderEmail() != null || r.reminderTime() != null
                || r.vocabListView() != null || Boolean.TRUE.equals(r.tourDone())
                || Boolean.TRUE.equals(r.swipeHintDone());
    }

    private static DayOfWeek oneDay(String code) {
        Set<DayOfWeek> days = JpathDays.parseCodes(List.of(code));
        return days.iterator().next();
    }

    private static String oneOf(String value, List<String> allowed, String message) {
        if (value == null) {
            return null;
        }
        String key = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(key)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, message);
        }
        return key;
    }

    private static LocalTime reminderTime(String value) {
        String time = value.trim();
        if (!JpathProperties.Reminder.TIME_PATTERN.matcher(time).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Giờ nhắc học phải theo dạng HH:mm, bước 5 phút.");
        }
        return LocalTime.parse(time);
    }
}
