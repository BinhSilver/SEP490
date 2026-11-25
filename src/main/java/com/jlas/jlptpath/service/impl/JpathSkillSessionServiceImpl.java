package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathSkillSessionStartRequest;
import com.jlas.jlptpath.dto.JpathSkillSessionView;
import com.jlas.jlptpath.entity.JpathCheckSet;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathDailyTab;
import com.jlas.jlptpath.entity.JpathSkillSession;
import com.jlas.jlptpath.entity.JpathSkillType;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.enums.JpathSkillSessionKind;
import com.jlas.jlptpath.enums.JpathSkillStep;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathCheckSetRepository;
import com.jlas.jlptpath.repository.JpathDailyPlanRepository;
import com.jlas.jlptpath.repository.JpathDailyTabRepository;
import com.jlas.jlptpath.repository.JpathSkillSessionRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathDailyPlanService;
import com.jlas.jlptpath.service.JpathRewardHook;
import com.jlas.jlptpath.service.JpathSkillCheckIssuer;
import com.jlas.jlptpath.service.JpathSkillSessionService;
import com.jlas.jlptpath.service.impl.JpathSkillContentReader.VisibleSet;
import com.jlas.jlptpath.service.impl.JpathSkillPicker.LevelContent;
import com.jlas.jlptpath.util.JpathSkillPolicy;
import com.jlas.jlptpath.util.JpathSkillRotation.Pick;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [JLAS-W0108] Skill sessions. Lock order (plan 2 Task 31 contract): {@link #start} is not transactional itself — it
 * first runs {@link JpathDailyPlanService#ensurePlanExists} (the plan insert's own transaction), then one writer
 * transaction takes path {@code findActiveForUpdate} → {@link JpathDailyPlanService#lockToday} (level, then plan) and
 * only then looks for the open session, so two parallel starts serialise on the path row and the second one finds
 * the first one's session (uk_jpath_skill_open is the database backstop). {@link #advance} takes the path lock first
 * too. Checks are issued through {@link JpathSkillCheckIssuer} when such a bean exists.
 */
@Service
public class JpathSkillSessionServiceImpl implements JpathSkillSessionService {

    static final String SUBMITTED = "SUBMITTED";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JpathDailyPlanService dailyPlans;
    private final JpathSkillContentReader reader;
    private final JpathSkillPicker picker;
    private final JpathUserPathRepository paths;
    private final JpathSkillSessionRepository sessions;
    private final JpathCheckSetRepository checks;
    private final JpathClock clock;
    private final Optional<JpathSkillCheckIssuer> issuer;
    // Plan 3 Task 16 (skill day): field-injected so the constructor stays as it was
    @Autowired
    private JpathUserLevelRepository levels;
    @Autowired
    private JpathDailyPlanRepository plans;
    @Autowired
    private JpathDailyTabRepository dailyTabs;
    @Autowired
    private ObjectProvider<JpathRewardHook> hooks;
    @Autowired
    private ObjectProvider<JpathDayCompletion> completion;
    // F238: carried skill sessions (field-injected so the constructor stays as it was)
    @Autowired(required = false)
    private com.jlas.jlptpath.service.JpathCalendarService calendar;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public JpathSkillSessionServiceImpl(JpathDailyPlanService dailyPlans, JpathSkillContentReader reader,
            JpathSkillPicker picker, JpathUserPathRepository paths, JpathSkillSessionRepository sessions,
            JpathCheckSetRepository checks, JpathClock clock, Optional<JpathSkillCheckIssuer> issuer,
            PlatformTransactionManager transactionManager) {
        this.dailyPlans = dailyPlans;
        this.reader = reader;
        this.picker = picker;
        this.paths = paths;
        this.sessions = sessions;
        this.checks = checks;
        this.clock = clock;
        this.issuer = issuer;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    // ---- start ---------------------------------------------------------------------------------------------

    @Override
    public JpathSkillSessionView start(Long userId, JpathSkillSessionStartRequest r) {
        if (r == null || r.skill() == null || r.kind() == null || r.kind() == JpathSkillSessionKind.SECTION
                || r.kind() == JpathSkillSessionKind.FREE && (r.typeCode() == null || r.typeCode().isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        // the plan insert's own transaction, before the writer transaction below (never under the path lock)
        dailyPlans.ensurePlanExists(userId);
        Long id = tx.execute(status -> startLocked(userId, r));
        return readTx.execute(status -> view(userId, sessions.findById(id).orElseThrow()));
    }

    private Long startLocked(Long userId, JpathSkillSessionStartRequest r) {
        // the path lock is the first statement: later plain reads then see rows committed while this one waited
        JpathUserPath path = paths.findActiveForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
        JpathDailyPlan plan = dailyPlans.lockToday(path);
        // F177 (AI test L7): a free session resumes only when it is the type the learner picked; "Học" on 情報検索 used to
        // reopen an unfinished 短文 session of the same skill
        boolean byType = r.kind() != JpathSkillSessionKind.DAY && r.typeCode() != null;
        Optional<JpathSkillSession> open = byType
                ? sessions.findFirstByUserIdAndPlanIdAndSkillAndKindAndTypeCodeAndCompletedAtIsNullOrderByIdDesc(userId,
                        plan.getId(), r.skill(), r.kind(), r.typeCode())
                : sessions.findFirstByUserIdAndPlanIdAndSkillAndKindAndCompletedAtIsNullOrderByIdDesc(userId, plan.getId(),
                        r.skill(), r.kind());
        if (open.isPresent()) {
            return open.get().getId();
        }
        JpathSkillType type;
        List<VisibleSet> sets;
        if (r.kind() == JpathSkillSessionKind.DAY) {
            if (plan.getDayKind() != JpathDayKind.SKILL) {
                // F238: a normal study day takes one session carried from earlier weeks, nothing else
                JpathSkillCarry.Carry carry = carryOf(path, plan.getStudyDay());
                if (!carry.offerToday() && carry.owed() <= 0) {
                    throw new ApiException(ErrorCode.JPATH_SKILL_NOT_SKILL_DAY);
                }
                if (!carry.offerToday()) {
                    Optional<JpathSkillSession> paidToday = sessions
                            .findFirstByUserIdAndPlanIdAndSkillAndKindAndCompletedAtIsNotNullOrderByIdDesc(userId,
                                    plan.getId(), r.skill(), JpathSkillSessionKind.DAY);
                    if (paidToday.isPresent()) {
                        return paidToday.get().getId();
                    }
                    throw new ApiException(ErrorCode.JPATH_SKILL_NOT_SKILL_DAY);
                }
            }
            Optional<JpathSkillSession> doneToday = sessions
                    .findFirstByUserIdAndPlanIdAndSkillAndKindAndCompletedAtIsNotNullOrderByIdDesc(userId,
                            plan.getId(), r.skill(), JpathSkillSessionKind.DAY);
            if (doneToday.isPresent()) {
                return doneToday.get().getId();
            }
            LevelContent content = picker.content(plan.getLevel());
            String code = picker.nextType(userId, content, r.skill())
                    .orElseThrow(() -> new ApiException(ErrorCode.JPATH_SKILL_NO_SETS));
            type = content.types().stream().filter(t -> t.getCode().equals(code)).findFirst().orElseThrow();
            sets = content.sets().getOrDefault(code, List.of());
        } else {
            type = freeType(path, r);
            sets = reader.visibleSets(type);
        }
        Pick pick = picker.pick(userId, type, sets, path.getDailyMinutes(), plan.getStudyDay());
        if (pick.codes().isEmpty()) {
            throw new ApiException(ErrorCode.JPATH_SKILL_NO_SETS);
        }
        JpathSkillSession s = sessions.saveAndFlush(JpathSkillSession.builder().userId(userId).pathId(path.getId())
                .planId(plan.getId()).level(type.getLevel()).skill(type.getSkill()).kind(r.kind())
                .typeCode(type.getCode()).step(JpathSkillStep.THEORY).setCodesJson(json(pick.codes()))
                .reused(pick.reused()).studyDay(plan.getStudyDay()).startedAt(clock.now()).build());
        ensureTokens(s, true);
        return s.getId();
    }

    @Override
    public com.jlas.jlptpath.dto.JpathSkillCarryView carry(Long userId) {
        return readTx.execute(status -> {
            JpathUserPath path = paths.findFirstByUserIdAndStatusAndArchivedAtIsNullOrderByIdDesc(userId,
                    com.jlas.jlptpath.enums.JpathPathStatus.ACTIVE).orElse(null);
            if (path == null) {
                return new com.jlas.jlptpath.dto.JpathSkillCarryView(0, "READING", false, false);
            }
            java.time.LocalDate today = clock.today(userId);
            JpathSkillCarry.Carry carry = carryOf(path, today);
            JpathDailyPlan plan = plans == null ? null : plans.findByUserIdAndStudyDay(userId, today).orElse(null);
            boolean studyDay = plan == null || plan.getDayKind() == JpathDayKind.STUDY;
            return new com.jlas.jlptpath.dto.JpathSkillCarryView(carry.owed(), carry.skill().name(),
                    studyDay && carry.offerToday(), carry.doneToday());
        });
    }

    /** F238: carried skill sessions of the path on {@code day} (none without the calendar, e.g. hand-built tests). */
    private JpathSkillCarry.Carry carryOf(JpathUserPath path, java.time.LocalDate day) {
        if (calendar == null || plans == null || levels == null) {
            return JpathSkillCarry.Carry.NONE;
        }
        com.jlas.jlptpath.enums.JpathStage stage = levels.findById(new com.jlas.jlptpath.entity.JpathUserLevelId(path.getId(), path.getOpenLevel()))
                .map(com.jlas.jlptpath.entity.JpathUserLevel::getStage).orElse(null);
        if (stage == null) {
            return JpathSkillCarry.Carry.NONE;
        }
        return JpathSkillCarry.compute(calendar, plans, sessions, path, stage, day);
    }

    /** FREE: a visible type of the requested skill, at the open level or a lower one, whose lesson is visible. */
    private JpathSkillType freeType(JpathUserPath path, JpathSkillSessionStartRequest r) {
        JpathSkillType type = reader.type(r.typeCode())
                .filter(t -> t.getSkill() == r.skill())
                .filter(t -> rank(t.getLevel()) >= rank(path.getOpenLevel()))
                .filter(t -> reader.lesson(t.getCode()).isPresent())
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_SKILL_TYPE_NOT_FOUND));
        return type;
    }

    // ---- get / advance -------------------------------------------------------------------------------------

    @Override
    public JpathSkillSessionView get(Long userId, Long sessionId) {
        return readTx.execute(status -> view(userId, own(userId, sessionId)));
    }

    @Override
    public JpathSkillSessionView advance(Long userId, Long sessionId) {
        return tx.execute(status -> {
            JpathUserPath path = paths.findActiveForUpdate(userId).orElse(null);
            JpathSkillSession s = own(userId, sessionId); // another learner's session stays NOT_FOUND
            if (path == null) {
                throw new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH);
            }
            switch (s.getStep()) {
                case THEORY -> {
                    if (s.getLessonPassedAt() == null) {
                        throw new ApiException(ErrorCode.JPATH_SKILL_LESSON_REQUIRED);
                    }
                    s.setStep(JpathSkillStep.PRACTICE);
                    ensureTokens(s, false);
                }
                case PRACTICE -> {
                    ensureTokens(s, false);
                    Optional<JpathCheckSet> practice = submitted(userId, s.getPracticeToken());
                    if (practice.isPresent() && s.getKind() == JpathSkillSessionKind.DAY
                            && pct(practice.get()) < JpathSkillPolicy.DAY_PASS_PCT) {
                        // Q7 (owner 24/11): a skill day counts only from 50% on the practice; below it the learner
                        // gets a fresh practice of the same sets (no limit), the failed score shown on the page
                        s.setScorePct(java.math.BigDecimal.valueOf(pct(practice.get())));
                        s.setPracticeToken(null);
                        ensureTokens(s, false);
                    } else if (practice.isPresent()) {
                        if (s.getSkill() == JpathSkill.LISTENING) {
                            s.setStep(JpathSkillStep.DICTATION);
                        } else {
                            finish(path, s, practice.get());
                        }
                    }
                }
                case DICTATION -> {
                    ensureTokens(s, false);
                    boolean noLines = s.getDictationToken() == null && issuer.isPresent();
                    if (noLines || submitted(userId, s.getDictationToken()).isPresent()) {
                        finish(path, s, submitted(userId, s.getPracticeToken()).orElse(null));
                    }
                }
                case DONE -> {
                    // nothing left
                }
            }
            sessions.saveAndFlush(s);
            return view(userId, s);
        });
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private void finish(JpathUserPath path, JpathSkillSession s, JpathCheckSet practice) {
        s.setStep(JpathSkillStep.DONE);
        s.setCompletedAt(clock.now());
        if (practice != null) {
            s.setScorePct(practice.getScorePct());
        }
        if (s.getKind() == JpathSkillSessionKind.DAY) {
            countSkillDay(path, s);
        }
    }

    /**
     * Plan 3 Task 16: a DAY session done counts once on the SKILL tab of the plan of its {@code study_day} (locks
     * after the path: open level, that plan, its tabs), pays #44 +2 for the skill when that day is still the
     * learner's today (a session finished after midnight counts for the old day without reward) and lets the day
     * completion decide the SKILL tab and the day (+2 and streak, KH2 Task 32).
     */
    private void countSkillDay(JpathUserPath path, JpathSkillSession s) {
        Long userId = s.getUserId();
        if (levels == null || plans == null || dailyTabs == null) {
            return; // built by hand without the plan repositories (unit tests)
        }
        levels.findForUpdate(path.getId(), path.getOpenLevel());
        JpathDailyPlan plan = plans.findForUpdate(userId, s.getStudyDay())
                .filter(p -> p.getId().equals(s.getPlanId())).orElse(null);
        if (plan == null) {
            return;
        }
        List<JpathDailyTab> tabs = dailyTabs.findForUpdateByPlanId(plan.getId());
        JpathDailyTab skill = tabs.stream().filter(t -> t.getTab() == JpathTab.SKILL).findFirst().orElse(null);
        if (skill == null) {
            return;
        }
        skill.setNewDone(skill.getNewDone() + 1);
        boolean sameDay = s.getStudyDay().equals(clock.today(userId));
        JpathRewardHook hook = hooks == null ? null : hooks.getIfAvailable();
        if (sameDay && hook != null) {
            hook.onSkillSessionDone(userId, s.getStudyDay(), s.getSkill());
        }
        JpathDayCompletion days = completion == null ? null : completion.getIfAvailable();
        if (days != null) {
            days.evaluate(userId, plan, tabs, JpathTab.SKILL, sameDay);
        }
    }

    /** Issues the missing checks through the port (dictation only for listening); a no-op without a port. */
    private void ensureTokens(JpathSkillSession s, boolean save) {
        if (issuer.isEmpty()) {
            return;
        }
        boolean changed = false;
        if (s.getPracticeToken() == null) {
            s.setPracticeToken(issuer.get().issuePractice(s));
            changed = true;
        }
        if (s.getSkill() == JpathSkill.LISTENING && s.getDictationToken() == null) {
            s.setDictationToken(issuer.get().issueDictation(s));
            changed = true;
        }
        if (changed && save) {
            sessions.saveAndFlush(s);
        }
    }

    private static int pct(JpathCheckSet c) {
        if (c.getScorePct() != null) return c.getScorePct().intValue();
        Integer total = c.getTotalCount(), ok = c.getCorrectCount();
        return total == null || total == 0 || ok == null ? 100 : Math.round(100f * ok / total);
    }

    private Optional<JpathCheckSet> submitted(Long userId, String token) {
        return checkSet(userId, token).filter(c -> SUBMITTED.equals(c.getStatus()));
    }

    private JpathSkillSession own(Long userId, Long sessionId) {
        if (sessionId == null) {
            throw new ApiException(ErrorCode.JPATH_SKILL_SESSION_NOT_FOUND);
        }
        return sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_SKILL_SESSION_NOT_FOUND));
    }

    private JpathSkillSessionView view(Long userId, JpathSkillSession s) {
        List<String> codes = codes(s.getSetCodesJson());
        Optional<JpathCheckSet> practiceCheck = checkSet(userId, s.getPracticeToken());
        Optional<JpathCheckSet> dictationCheck = checkSet(userId, s.getDictationToken());
        Integer practiceTotal = practiceCheck.map(JpathCheckSet::getTotalCount).orElse(null);
        Integer dictationTotal = dictationCheck.map(JpathCheckSet::getTotalCount).orElse(null);
        Integer practiceCorrect = practiceCheck.filter(c -> SUBMITTED.equals(c.getStatus()))
                .map(JpathCheckSet::getCorrectCount).orElse(null);
        Integer dictationCorrect = dictationCheck.filter(c -> SUBMITTED.equals(c.getStatus()))
                .map(JpathCheckSet::getCorrectCount).orElse(null);
        int questions = practiceTotal != null ? practiceTotal : reader.visibleQuestions(codes).size();
        int dictation = dictationTotal != null ? dictationTotal
                : s.getSkill() == JpathSkill.LISTENING ? JpathSkillPolicy.DICTATION_FLOOR : 0;
        return new JpathSkillSessionView(s.getId(), s.getSkill(), s.getKind(), s.getTypeCode(), s.getStep(),
                codes.size(), questions, dictation, s.isReused(), s.getPracticeToken(), s.getDictationToken(),
                s.getCompletedAt() != null, s.getCpAwarded() == null ? 0 : s.getCpAwarded(), s.getStudyDay(),
                practiceCorrect, dictationCorrect,
                s.getStep() == JpathSkillStep.PRACTICE && s.getKind() == JpathSkillSessionKind.DAY ? toInt(s.getScorePct()) : null);
    }

    private static Integer toInt(Number n) {
        return n == null ? null : n.intValue();
    }

    private Optional<JpathCheckSet> checkSet(Long userId, String token) {
        return token == null ? Optional.empty() : checks.findByTokenAndUserId(token, userId);
    }

    /** N5 → 5 … N1 → 1 (a larger rank is a lower level). */
    private static int rank(String level) {
        return level == null || level.length() != 2 ? 0 : level.charAt(1) - '0';
    }

    private static String json(List<String> codes) {
        try {
            return JSON.writeValueAsString(codes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> codes(String json) {
        try {
            return json == null ? List.of() : JSON.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }
}
