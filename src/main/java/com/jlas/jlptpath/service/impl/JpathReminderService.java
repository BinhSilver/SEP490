package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.config.JpathProperties;
import com.jlas.jlptpath.dto.JpathReminderFacts;
import com.jlas.jlptpath.dto.JpathReminderMessage;
import com.jlas.jlptpath.dto.JpathTabProgressView;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathReminderLog;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserLevelId;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.entity.JpathUserSettings;
import com.jlas.jlptpath.entity.JpathSkillType;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.enums.JpathStage;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathDailyPlanRepository;
import com.jlas.jlptpath.repository.JpathReminderLogRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.service.JpathCalendarService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathProgressService;
import com.jlas.jlptpath.util.JpathReminderComposer;
import com.jlas.notification.service.UserNotificationPort;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] One combined study reminder per learner day (policy #23): after the learner's reminder time, not on a
 * rest day, not once today is done; in-web through the notification module, and by email when both the learner and
 * {@code reminder.email-enabled} allow it. The day is logged in jpath_reminder_log (learner's date), so a zone change
 * on the same learner day sends nothing more. Reads only (plus the log row): no path, level or plan lock is taken.
 * Today's kind comes from today's stored plan when there is one, else from {@link JpathCalendarService#dayKind}.
 */
@Service
public class JpathReminderService {

    static final String TYPE = "JPATH_REMINDER";
    static final String TARGET_URL = "/learning";
    static final String EVENT_PREFIX = "jpath-reminder-";
    private static final List<String> LEVELS_UP = List.of("N5", "N4", "N3", "N2", "N1");
    private static final Set<JpathTab> LEVEL_TABS = Set.of(JpathTab.VOCAB, JpathTab.KANJI, JpathTab.GRAMMAR);

    private final JpathClock clock;
    private final JpathReminderLogRepository logs;
    private final JpathDailyPlanRepository plans;
    private final JpathUserLevelRepository levels;
    private final JpathUserItemRepository items;
    private final JpathCalendarService calendar;
    private final JpathProgressService progress;
    private final UserNotificationPort notifications;
    private final JpathReminderMailer mailer;
    private final JpathProperties props;

    public JpathReminderService(JpathClock clock, JpathReminderLogRepository logs, JpathDailyPlanRepository plans,
            JpathUserLevelRepository levels, JpathUserItemRepository items, JpathCalendarService calendar,
            JpathProgressService progress, UserNotificationPort notifications, JpathReminderMailer mailer,
            JpathProperties props) {
        this.clock = clock;
        this.logs = logs;
        this.plans = plans;
        this.levels = levels;
        this.items = items;
        this.calendar = calendar;
        this.progress = progress;
        this.notifications = notifications;
        this.mailer = mailer;
        this.props = props;
    }

    /** Sends today's reminder when it is due; true when at least one channel went out (and the day was logged). */
    @Transactional
    public boolean remind(JpathUserPath path, JpathUserSettings settings) {
        if (!settings.isReminderWeb() && !settings.isReminderEmail()) {
            return false;
        }
        Long userId = path.getUserId();
        ZonedDateTime now = clock.zonedNow(userId);
        if (now.toLocalTime().isBefore(settings.getReminderTime())) {
            return false;
        }
        LocalDate today = now.toLocalDate();
        if (logs.existsByUserIdAndReminderDay(userId, today)) {
            return false;
        }
        Optional<JpathDailyPlan> plan = plans.findByUserIdAndStudyDay(userId, today);
        JpathDayKind kind = plan.map(JpathDailyPlan::getDayKind)
                .orElseGet(() -> calendar.dayKind(path, openStage(path), today));
        if (kind == JpathDayKind.REST || plan.map(JpathDailyPlan::getAllDoneAt).isPresent()) {
            return false;
        }
        boolean web = settings.isReminderWeb();
        boolean email = settings.isReminderEmail() && props.reminder().emailEnabled();
        if (!web && !email) {
            return false;
        }
        JpathReminderMessage msg = JpathReminderComposer.compose(facts(path, kind, today, now));
        List<String> channels = new ArrayList<>(2);
        if (web) {
            notifications.notifyUser(userId, TYPE, msg.title(), msg.message(), TARGET_URL, EVENT_PREFIX + today);
            channels.add("WEB");
        }
        if (email && mailer.send(userId, msg.title(), msg.message())) {
            channels.add("EMAIL");
        }
        if (channels.isEmpty()) {
            return false;
        }
        logs.save(JpathReminderLog.builder().userId(userId).reminderDay(today).sentAt(clock.now())
                .channels(String.join(",", channels)).build());
        return true;
    }

    private JpathStage openStage(JpathUserPath path) {
        return levels.findById(new JpathUserLevelId(path.getId(), path.getOpenLevel()))
                .map(JpathUserLevel::getStage).orElse(JpathStage.STUDY);
    }

    private JpathReminderFacts facts(JpathUserPath path, JpathDayKind kind, LocalDate today, ZonedDateTime now) {
        Long userId = path.getUserId();
        int due = (int) items.countDue(userId, now.toLocalDateTime());
        Integer daysToExam = path.getExamDate() == null ? null
                : (int) ChronoUnit.DAYS.between(today, path.getExamDate());
        String lower = firstWeekOfOpenLevel(path, today) ? lowerLevel(path) : null;
        int lowerLeft = lower == null ? 0 : notLearned(userId, lower);
        boolean skillDay = kind == JpathDayKind.SKILL;
        String reading = skillDay ? todayType(path, JpathSkill.READING) : null;
        String listening = skillDay ? todayType(path, JpathSkill.LISTENING) : null;
        return new JpathReminderFacts(due, skillDay, daysToExam, lowerLeft, lower, reading, listening);
    }

    // Plan 3 Task 24: field-injected so the constructor (and its tests) stay as they were
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private JpathSkillPicker skillPicker;

    /** Name of the type a skill-day session of {@code skill} would start with now; null when none or unknown. */
    private String todayType(JpathUserPath path, JpathSkill skill) {
        if (skillPicker == null) {
            return null;
        }
        JpathSkillPicker.LevelContent content = skillPicker.content(path.getOpenLevel());
        return skillPicker.nextType(path.getUserId(), content, skill)
                .flatMap(code -> content.types().stream().filter(t -> t.getCode().equals(code)).findFirst())
                .map(JpathSkillType::getNameVi).orElse(null);
    }

    /**
     * Plan 3 Task 24: the lower level still to learn is mentioned once, in the first reminder of the first week of the
     * open level (no reminder sent since the level opened).
     */
    private boolean firstWeekOfOpenLevel(JpathUserPath path, LocalDate today) {
        LocalDate opened = levels.findById(new JpathUserLevelId(path.getId(), path.getOpenLevel()))
                .map(JpathUserLevel::getOpenedAt).map(java.time.LocalDateTime::toLocalDate).orElse(null);
        if (opened == null || today.isBefore(opened) || !today.isBefore(opened.plusDays(7))) {
            return false;
        }
        return !logs.existsByUserIdAndReminderDayBetween(path.getUserId(), opened, today.minusDays(1));
    }

    /** The level just below the open one, when the learner has a row for it in this path (it was studied). */
    private String lowerLevel(JpathUserPath path) {
        int open = LEVELS_UP.indexOf(path.getOpenLevel());
        if (open <= 0) {
            return null;
        }
        String lower = LEVELS_UP.get(open - 1);
        return levels.existsById(new JpathUserLevelId(path.getId(), lower)) ? lower : null;
    }

    /** Learnable vocabulary, kanji and grammar items of {@code level} not counted as learned yet. */
    private int notLearned(Long userId, String level) {
        int left = 0;
        for (JpathTabProgressView tab : progress.level(userId, level).tabs()) {
            if (LEVEL_TABS.contains(tab.tab())) {
                left += Math.max(0, tab.total() - tab.counted());
            }
        }
        return left;
    }
}
