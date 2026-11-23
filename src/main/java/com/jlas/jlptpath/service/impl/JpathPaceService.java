package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.dto.JpathPaceInput;
import com.jlas.jlptpath.dto.JpathPaceLevel;
import com.jlas.jlptpath.dto.JpathPaceResult;
import com.jlas.jlptpath.dto.JpathPaceView;
import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.dto.JpathTabCount;
import com.jlas.jlptpath.dto.JpathWarningView;
import com.jlas.jlptpath.enums.JpathStudyMode;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathLevelInfo;
import com.jlas.jlptpath.util.JpathPacingEngine;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Pace estimate of a path (policy #5/#6/#33) with the learner's counts and the notices shown with it.
 * Shared by the confirmation screen, the estimate API, path creation and the path view.
 */
@Component
public class JpathPaceService {

    static final String EXAM_DATE_PASSED_VI = "Ngày thi đã qua. Bạn có thể đặt ngày thi mới trong Cài đặt.";
    private static final List<String> LEVELS = List.of("N5", "N4", "N3", "N2", "N1");

    private final JpathCatalogService catalog;
    private final JpathTabCountsReader tabCounts;

    public JpathPaceService(JpathCatalogService catalog, JpathTabCountsReader tabCounts) {
        this.catalog = catalog;
        this.tabCounts = tabCounts;
    }

    /**
     * What to estimate. {@code examDate} may be past (it is then dropped with EXAM_DATE_PASSED); {@code thirdSkillDay}
     * null = automatic; {@code mode} null = COMBINED; {@code skillPracticeSince} = the day the open level entered
     * skill practice, null when it has not; {@code openLevelCounts} = the open level's tab counts when the caller
     * already read them (null = read here).
     */
    public record Query(LocalDate today, String openLevel, String targetLevel, LocalDate examDate, int minutes,
            Set<DayOfWeek> skillDays, Set<DayOfWeek> restDays, DayOfWeek thirdSkillDay, JpathSliderValues sliders,
            int kanaRowsLeft, JpathStudyMode mode, LocalDate skillPracticeSince, boolean enforceTarget,
            Map<JpathTab, JpathTabCount> openLevelCounts) {
    }

    /**
     * Pace estimate. An exam date on or before today is not used and adds EXAM_DATE_PASSED (#33). F230: the block is
     * decided on the words per day the learner chose, so a blocked target is always late too.
     */
    @Transactional(readOnly = true)
    public JpathPaceView pace(Long userId, Query q) {
        LocalDate exam = usableExamDate(q.examDate(), q.today());
        JpathPaceInput in = input(userId, q, exam, q.sliders());
        JpathPaceResult result = JpathPacingEngine.estimate(in);
        JpathSliderValues balanced = JpathPacingEngine.balance(in.levels().get(0), q.sliders().vocab());

        int minutes = q.minutes();
        String targetLevel = q.targetLevel();
        List<JpathWarningView> warnings = new ArrayList<>();
        if (q.examDate() != null && exam == null) {
            warnings.add(new JpathWarningView("EXAM_DATE_PASSED", EXAM_DATE_PASSED_VI));
        }
        if (!result.blocked() && result.late()) {
            warnings.add(new JpathWarningView("LATE", "Với số mục mỗi ngày đang chọn, bạn chưa kịp xong "
                    + targetLevel + " trước ngày thi."));
        }
        // F230: no OVER_BUDGET warning — the learner picks words, the minutes shown are only an estimate
        if (!result.blocked() && result.skippedLowPriority() > 0) {
            warnings.add(new JpathWarningView("PRIORITY_MODE", "Sát ngày thi: lộ trình học mục hay gặp trước, "
                    + "bạn sẽ bỏ qua khoảng " + result.skippedLowPriority() + " mục ít gặp."));
        }
        return new JpathPaceView(result.tabFinish(), result.levelFinish(), result.targetFinish(), result.blocked(),
                result.late(), result.overBudget(), result.minutesPerDay(), minutes, result.suggestions(),
                result.skippedLowPriority(), result.messageVi(), List.copyOf(warnings), balanced);
    }

    /** F230: Kanji and grammar per day that finish the level's left items together with {@code vocabPerDay} words. */
    @Transactional(readOnly = true)
    public JpathSliderValues balanced(Long userId, String level, int vocabPerDay) {
        Map<JpathTab, JpathTabCount> c = tabCounts.read(userId, level);
        return JpathPacingEngine.balance(level, vocabPerDay, left(c.get(JpathTab.VOCAB)), left(c.get(JpathTab.KANJI)),
                left(c.get(JpathTab.GRAMMAR)));
    }

    private static int left(JpathTabCount c) {
        return c == null ? 0 : (int) Math.max(0, c.total() - c.counted());
    }

    /** F237: the engine input of {@code q} (exam dates on or before today dropped), for rules built on the estimate. */
    @Transactional(readOnly = true)
    public JpathPaceInput engineInput(Long userId, Query q) {
        return input(userId, q, usableExamDate(q.examDate(), q.today()), q.sliders());
    }

    /** Exam dates on or before today are not used (policy #33). */
    public static LocalDate usableExamDate(LocalDate examDate, LocalDate today) {
        return examDate != null && examDate.isAfter(today) ? examDate : null;
    }

    /**
     * Engine input from the open level to the target: released levels use the learnable totals and the learner's
     * counted items; levels not released yet use the catalogue's planned totals (policy #5). The mode is never null.
     */
    private JpathPaceInput input(Long userId, Query q, LocalDate exam, JpathSliderValues sliders) {
        List<JpathPaceLevel> levels = new ArrayList<>();
        for (int i = LEVELS.indexOf(q.openLevel()); i <= LEVELS.indexOf(q.targetLevel()); i++) {
            String level = LEVELS.get(i);
            boolean open = i == LEVELS.indexOf(q.openLevel());
            LocalDate since = open ? q.skillPracticeSince() : null;
            if (catalog.isReleased(level)) {
                Map<JpathTab, JpathTabCount> counts = open && q.openLevelCounts() != null ? q.openLevelCounts()
                        : tabCounts.read(userId, level);
                JpathTabCount v = counts.get(JpathTab.VOCAB);
                JpathTabCount k = counts.get(JpathTab.KANJI);
                JpathTabCount g = counts.get(JpathTab.GRAMMAR);
                levels.add(new JpathPaceLevel(level, (int) v.total(), (int) k.total(), (int) g.total(),
                        (int) v.counted(), (int) k.counted(), (int) g.counted(), since));
            } else {
                JpathLevelInfo info = catalog.info(level);
                levels.add(new JpathPaceLevel(level, info.vocabCount(), info.kanjiCount(), info.grammarCount(),
                        0, 0, 0, since));
            }
        }
        return new JpathPaceInput(q.today(), levels, sliders, q.skillDays(), q.restDays(), q.thirdSkillDay(), exam,
                q.kanaRowsLeft(), q.mode() == null ? JpathStudyMode.COMBINED : q.mode(), q.minutes(),
                q.enforceTarget());
    }
}
