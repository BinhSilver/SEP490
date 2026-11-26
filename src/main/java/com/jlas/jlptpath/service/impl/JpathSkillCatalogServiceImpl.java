package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathReadiness;
import com.jlas.jlptpath.dto.JpathSkillOverview.SkillCard;
import com.jlas.jlptpath.dto.JpathSkillOverview.TypeRow;
import com.jlas.jlptpath.dto.JpathSkillOverview;
import com.jlas.jlptpath.dto.JpathTodayTab;
import com.jlas.jlptpath.dto.JpathTodayView;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathSkillSession;
import com.jlas.jlptpath.entity.JpathSkillType;
import com.jlas.jlptpath.entity.JpathSkillTypeStat;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserLevelId;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.enums.JpathSkillSessionKind;
import com.jlas.jlptpath.enums.JpathStage;
import com.jlas.jlptpath.repository.JpathDailyPlanRepository;
import com.jlas.jlptpath.repository.JpathSkillSessionRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathCalendarService;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathDailyPlanService;
import com.jlas.jlptpath.service.JpathSkillCatalogService;
import com.jlas.jlptpath.service.impl.JpathSkillContentReader.VisibleSet;
import com.jlas.jlptpath.service.impl.JpathSkillPicker.LevelContent;
import com.jlas.jlptpath.util.JpathSkillPolicy;
import com.jlas.jlptpath.util.JpathSkillRotation.Pick;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [JLAS-W0108] The Đọc · Nghe tab. {@link #overview} first runs {@link JpathDailyPlanService#today} (which creates
 * today's plan in its own transaction), then reads everything else in one read-only transaction; it never writes.
 * Type of the day #37 and the minute estimate (#34 picks, budget daily minutes / 2) are previews: a session started
 * later picks the same way.
 */
@Service
public class JpathSkillCatalogServiceImpl implements JpathSkillCatalogService {

    private static final Set<JpathStage> SECTION_STAGES = EnumSet.of(JpathStage.SKILL_PRACTICE, JpathStage.EXAM,
            JpathStage.PASSED);

    private final JpathCatalogService catalog;
    private final JpathSkillPicker picker;
    private final JpathDailyPlanService dailyPlans;
    private final JpathCalendarService calendar;
    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository levels;
    private final JpathDailyPlanRepository plans;
    private final JpathSkillSessionRepository sessions;
    private final JpathClock clock;
    private final TransactionTemplate readTx;
    // Plan 3 Task 15 readiness; field-injected so the constructor (and its tests) stay as they were
    @Autowired
    private ObjectProvider<JpathSectionPracticeServiceImpl> sectionPractice;

    public JpathSkillCatalogServiceImpl(JpathCatalogService catalog, JpathSkillPicker picker, JpathDailyPlanService dailyPlans, JpathCalendarService calendar,
            JpathUserPathRepository paths, JpathUserLevelRepository levels, JpathDailyPlanRepository plans,
            JpathSkillSessionRepository sessions, JpathClock clock, PlatformTransactionManager transactionManager) {
        this.catalog = catalog;
        this.picker = picker;
        this.dailyPlans = dailyPlans;
        this.calendar = calendar;
        this.paths = paths;
        this.levels = levels;
        this.plans = plans;
        this.sessions = sessions;
        this.clock = clock;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    @Override
    public JpathSkillOverview overview(Long userId, String level) {
        String wanted = level == null || level.isBlank() ? null : released(level);
        JpathTodayView today = dailyPlans.today(userId);
        return readTx.execute(status -> build(userId, wanted, today));
    }

    private JpathSkillOverview build(Long userId, String wanted, JpathTodayView todayView) {
        JpathUserPath path = paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
        String level = wanted == null ? released(path.getOpenLevel()) : wanted;
        JpathStage stage = stage(path, level);
        JpathStage openStage = stage(path, path.getOpenLevel());
        LocalDate today = clock.today(userId);
        Optional<JpathDailyPlan> plan = plans.findByUserIdAndStudyDay(userId, today);
        boolean openLevel = level.equals(path.getOpenLevel());

        LevelContent content = picker.content(level);
        Map<String, JpathSkillTypeStat> stats = picker.stats(userId, content.types());
        List<TypeRow> rows = new ArrayList<>(content.types().size());
        for (JpathSkillType t : content.types()) {
            JpathSkillTypeStat s = stats.get(t.getCode());
            rows.add(new TypeRow(t.getCode(), t.getSkill(), t.getNameVi(), t.getNameJa(),
                    s == null ? 0 : s.getSessionsDone(),
                    s == null || s.getLastPct() == null ? null
                            : s.getLastPct().setScale(0, RoundingMode.HALF_UP).intValue(),
                    content.available(t)));
        }

        double[] minutes = new double[1];
        SkillCard reading = card(userId, path, level, content, plan, JpathSkill.READING, today, minutes);
        SkillCard listening = card(userId, path, level, content, plan, JpathSkill.LISTENING, today, minutes);

        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        int done = (int) sessions.countByUserIdAndKindAndStudyDayBetweenAndCompletedAtIsNotNull(userId,
                JpathSkillSessionKind.DAY, monday, monday.plusDays(6));
        int skillDays = 0;
        for (int i = 0; i < 7; i++) {
            if (calendar.dayKind(path, openStage, monday.plusDays(i)) == JpathDayKind.SKILL) {
                skillDays++;
            }
        }
        int skills = (content.hasAvailable(JpathSkill.READING) ? 1 : 0)
                + (content.hasAvailable(JpathSkill.LISTENING) ? 1 : 0);

        boolean skillDay = openLevel && todayView != null && todayView.kind() == JpathDayKind.SKILL;
        int reviewsDue = todayView == null || todayView.tabs() == null ? 0
                : todayView.tabs().stream().mapToInt(JpathTodayTab::reviewDue).sum();
        return new JpathSkillOverview(level, stage, skillDay, reviewsDue, (int) Math.ceil(minutes[0]), done,
                skillDays * skills, reading, listening, rows, stage != null && SECTION_STAGES.contains(stage),
                readiness(userId, level));
    }

    /** #41 through the section service (Task 15); null when that service is not in the context. */
    private JpathReadiness readiness(Long userId, String level) {
        JpathSectionPracticeServiceImpl s = sectionPractice == null ? null : sectionPractice.getIfAvailable();
        return s == null ? null : s.readinessOf(userId, level);
    }

    private SkillCard card(Long userId, JpathUserPath path, String level, LevelContent content,
            Optional<JpathDailyPlan> plan, JpathSkill skill, LocalDate today, double[] minutes) {
        JpathSkillPolicy.Floor floor = JpathSkillPolicy.floor(level, skill);
        Long planId = plan.map(JpathDailyPlan::getId).orElse(null);
        Optional<JpathSkillSession> open = planId == null ? Optional.empty()
                : sessions.findFirstByUserIdAndPlanIdAndSkillAndKindAndCompletedAtIsNullOrderByIdDesc(userId, planId,
                        skill, JpathSkillSessionKind.DAY).filter(s -> level.equals(s.getLevel()));
        Optional<JpathSkillSession> done = planId == null ? Optional.empty() : sessions
                .findFirstByUserIdAndPlanIdAndSkillAndKindAndCompletedAtIsNotNullOrderByIdDesc(userId, planId, skill,
                        JpathSkillSessionKind.DAY).filter(s -> level.equals(s.getLevel()));
        boolean doneToday = done.isPresent();
        // walkthrough 24/11: once today's session is done the card names the type learnt today, not the next one
        String typeCode = open.map(JpathSkillSession::getTypeCode)
                .or(() -> done.map(JpathSkillSession::getTypeCode))
                .orElseGet(() -> picker.nextType(userId, content, skill).orElse(null));
        JpathSkillType type = typeCode == null ? null
                : content.types().stream().filter(t -> t.getCode().equals(typeCode)).findFirst().orElse(null);
        if (type != null && content.available(type)) {
            List<VisibleSet> sets = content.sets().getOrDefault(type.getCode(), List.of());
            Pick pick = picker.pick(userId, type, sets, path.getDailyMinutes(), today);
            minutes[0] += JpathSkillPicker.minutes(type, sets, pick.codes());
        }
        return new SkillCard(skill, type == null ? null : type.getCode(), type == null ? null : type.getNameVi(),
                floor.passages(), floor.questions(), floor.dictation(), open.map(JpathSkillSession::getId).orElse(null),
                doneToday);
    }

    private JpathStage stage(JpathUserPath path, String level) {
        return levels.findById(new JpathUserLevelId(path.getId(), level)).map(JpathUserLevel::getStage)
                .orElse(JpathStage.STUDY);
    }

    /** Upper-cased level; BAD_REQUEST when not N1..N5, JPATH_LEVEL_NOT_RELEASED when not released. */
    private String released(String level) {
        String key = catalog.info(level.trim().toUpperCase(Locale.ROOT)).level();
        if (!catalog.isReleased(key)) {
            throw new ApiException(ErrorCode.JPATH_LEVEL_NOT_RELEASED);
        }
        return key;
    }
}
