package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathCheckView;
import com.jlas.jlptpath.dto.JpathReadiness;
import com.jlas.jlptpath.dto.JpathReadiness.SectionTry;
import com.jlas.jlptpath.entity.JpathCheckSet;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathSkillSession;
import com.jlas.jlptpath.entity.JpathSkillType;
import com.jlas.jlptpath.entity.JpathSkillTypeStat;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserLevelId;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.enums.JpathSkillSessionKind;
import com.jlas.jlptpath.enums.JpathSkillStep;
import com.jlas.jlptpath.enums.JpathStage;
import com.jlas.jlptpath.repository.JpathCheckSetRepository;
import com.jlas.jlptpath.repository.JpathSkillSessionRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathCheckService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathDailyPlanService;
import com.jlas.jlptpath.service.JpathSectionPracticeService;
import com.jlas.jlptpath.service.impl.JpathSkillPicker.LevelContent;
import com.jlas.jlptpath.util.JpathSkillRotation.Pick;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [JLAS-W0108] {@link JpathSectionPracticeService}. {@link #start} runs {@code ensurePlanExists} outside the
 * transaction, then one writer transaction: path {@code findActiveForUpdate} → {@code lockToday} (level, plan); the
 * section session is a {@link JpathSkillSessionKind#SECTION} skill session (one open per plan and skill) whose
 * {@code section_token} is the SKILL_SECTION check. A stale open section (its check submitted or out of time) is
 * closed before a new one starts. No CP, no plan change.
 */
@Service
public class JpathSectionPracticeServiceImpl implements JpathSectionPracticeService {

    static final Set<JpathStage> SECTION_STAGES = EnumSet.of(JpathStage.SKILL_PRACTICE, JpathStage.EXAM,
            JpathStage.PASSED);
    /** #41: passes needed in a row for each skill. */
    static final int READY_PASSES = 2;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository levels;
    private final JpathSkillSessionRepository sessions;
    private final JpathCheckSetRepository checkSets;
    private final JpathDailyPlanService dailyPlans;
    private final JpathSkillPicker picker;
    private final JpathSkillCheckBuilder builder;
    private final JpathCheckService checks;
    private final JpathClock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public JpathSectionPracticeServiceImpl(JpathUserPathRepository paths, JpathUserLevelRepository levels,
            JpathSkillSessionRepository sessions, JpathCheckSetRepository checkSets, JpathDailyPlanService dailyPlans,
            JpathSkillPicker picker, JpathSkillCheckBuilder builder, JpathCheckService checks, JpathClock clock,
            PlatformTransactionManager transactionManager) {
        this.paths = paths;
        this.levels = levels;
        this.sessions = sessions;
        this.checkSets = checkSets;
        this.dailyPlans = dailyPlans;
        this.picker = picker;
        this.builder = builder;
        this.checks = checks;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    // ---- start ---------------------------------------------------------------------------------------------

    @Override
    public JpathCheckView start(Long userId, JpathSkill skill) {
        if (skill == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        dailyPlans.ensurePlanExists(userId);
        String token = tx.execute(status -> startLocked(userId, skill));
        return checks.get(userId, token);
    }

    private String startLocked(Long userId, JpathSkill skill) {
        JpathUserPath path = paths.findActiveForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
        JpathDailyPlan plan = dailyPlans.lockToday(path);
        String level = path.getOpenLevel();
        JpathStage stage = levels.findById(new JpathUserLevelId(path.getId(), level)).map(JpathUserLevel::getStage)
                .orElse(JpathStage.STUDY);
        if (!SECTION_STAGES.contains(stage)) {
            throw new ApiException(ErrorCode.JPATH_SECTION_LOCKED);
        }
        Optional<JpathSkillSession> open = sessions
                .findFirstByUserIdAndPlanIdAndSkillAndKindAndCompletedAtIsNullOrderByIdDesc(userId, plan.getId(),
                        skill, JpathSkillSessionKind.SECTION);
        if (open.isPresent()) {
            JpathSkillSession s = open.get();
            Optional<JpathCheckSet> running = s.getSectionToken() == null ? Optional.empty()
                    : checkSets.findByTokenAndUserId(s.getSectionToken(), userId)
                            .filter(c -> JpathCheckServiceImpl.ISSUED.equals(c.getStatus()))
                            .filter(c -> !JpathCheckServiceImpl.pastDeadline(c, clock.now()));
            if (running.isPresent()) {
                return running.get().getToken();
            }
            s.setStep(JpathSkillStep.DONE);
            s.setCompletedAt(clock.now());
            sessions.saveAndFlush(s);
        }
        LevelContent content = picker.content(level);
        Pick pick = picker.pickSection(userId, content, skill, plan.getStudyDay());
        if (pick.codes().isEmpty()) {
            throw new ApiException(ErrorCode.JPATH_SKILL_NO_SETS);
        }
        JpathSkillSession s = sessions.saveAndFlush(JpathSkillSession.builder().userId(userId).pathId(path.getId())
                .planId(plan.getId()).level(level).skill(skill).kind(JpathSkillSessionKind.SECTION)
                .step(JpathSkillStep.PRACTICE).setCodesJson(JpathSkillCheckBuilder.json(pick.codes()))
                .reused(pick.reused()).studyDay(plan.getStudyDay()).startedAt(clock.now()).build());
        String token = builder.issueSection(s);
        s.setSectionToken(token);
        sessions.saveAndFlush(s);
        return token;
    }

    // ---- readiness -----------------------------------------------------------------------------------------

    @Override
    public JpathReadiness readiness(Long userId, String level) {
        return readTx.execute(status -> {
            String lvl = level == null || level.isBlank()
                    ? paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE)
                            .map(JpathUserPath::getOpenLevel)
                            .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH))
                    : level.trim().toUpperCase(Locale.ROOT);
            return readinessOf(userId, lvl);
        });
    }

    JpathReadiness readinessOf(Long userId, String level) {
        LevelContent content = picker.content(level);
        List<JpathSkillType> types = content.types().stream().filter(content::available).toList();
        Map<String, JpathSkillTypeStat> stats = picker.stats(userId, types);
        int done = (int) types.stream().map(t -> stats.get(t.getCode()))
                .filter(st -> st != null && st.getSessionsDone() != null && st.getSessionsDone() >= 1).count();
        boolean allTypes = !types.isEmpty() && done == types.size();
        List<SectionTry> reading = new ArrayList<>();
        List<SectionTry> listening = new ArrayList<>();
        for (JpathCheckSet c : checkSets.findTop20ByUserIdAndKindAndLevelAndStatusOrderBySubmittedAtDescIdDesc(userId,
                JpathCheckKind.SKILL_SECTION, level, JpathCheckServiceImpl.SUBMITTED)) {
            List<SectionTry> into = JpathSkill.LISTENING.name().equals(skillOf(c)) ? listening : reading;
            if (into.size() < READY_PASSES) {
                into.add(new SectionTry(c.getSubmittedAt(), c.getScorePct(), Boolean.TRUE.equals(c.getPassed())));
            }
        }
        boolean ready = allTypes
                && (!content.hasAvailable(JpathSkill.READING) || twoPassed(reading))
                && (!content.hasAvailable(JpathSkill.LISTENING) || twoPassed(listening));
        return new JpathReadiness(allTypes, done, types.size(), reading, listening, ready);
    }

    private static boolean twoPassed(List<SectionTry> tries) {
        return tries.size() == READY_PASSES && tries.stream().allMatch(SectionTry::passed);
    }

    private static String skillOf(JpathCheckSet c) {
        try {
            JsonNode skill = JSON.readTree(c.getIssueJson() == null ? "{}" : c.getIssueJson()).path("skill");
            return skill.isTextual() ? skill.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
