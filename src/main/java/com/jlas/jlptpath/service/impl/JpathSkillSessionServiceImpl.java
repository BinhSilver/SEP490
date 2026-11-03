package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathSkillSessionStartRequest;
import com.jlas.jlptpath.dto.JpathSkillSessionView;
import com.jlas.jlptpath.entity.JpathCheckSet;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathSkillSession;
import com.jlas.jlptpath.entity.JpathSkillType;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.enums.JpathSkillSessionKind;
import com.jlas.jlptpath.enums.JpathSkillStep;
import com.jlas.jlptpath.repository.JpathCheckSetRepository;
import com.jlas.jlptpath.repository.JpathSkillSessionRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathDailyPlanService;
import com.jlas.jlptpath.service.JpathSkillCheckIssuer;
import com.jlas.jlptpath.service.JpathSkillSessionService;
import com.jlas.jlptpath.service.impl.JpathSkillContentReader.VisibleSet;
import com.jlas.jlptpath.service.impl.JpathSkillPicker.LevelContent;
import com.jlas.jlptpath.util.JpathSkillPolicy;
import com.jlas.jlptpath.util.JpathSkillRotation.Pick;
import java.util.List;
import java.util.Optional;
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
        Optional<JpathSkillSession> open = sessions
                .findFirstByUserIdAndPlanIdAndSkillAndKindAndCompletedAtIsNullOrderByIdDesc(userId, plan.getId(),
                        r.skill(), r.kind());
        if (open.isPresent()) {
            return open.get().getId();
        }
        JpathSkillType type;
        List<VisibleSet> sets;
        if (r.kind() == JpathSkillSessionKind.DAY) {
            if (plan.getDayKind() != JpathDayKind.SKILL) {
                throw new ApiException(ErrorCode.JPATH_SKILL_NOT_SKILL_DAY);
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
            boolean active = paths.findActiveForUpdate(userId).isPresent();
            JpathSkillSession s = own(userId, sessionId); // another learner's session stays NOT_FOUND
            if (!active) {
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
                    if (practice.isPresent()) {
                        if (s.getSkill() == JpathSkill.LISTENING) {
                            s.setStep(JpathSkillStep.DICTATION);
                        } else {
                            finish(s, practice.get());
                        }
                    }
                }
                case DICTATION -> {
                    ensureTokens(s, false);
                    boolean noLines = s.getDictationToken() == null && issuer.isPresent();
                    if (noLines || submitted(userId, s.getDictationToken()).isPresent()) {
                        finish(s, submitted(userId, s.getPracticeToken()).orElse(null));
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

    private void finish(JpathSkillSession s, JpathCheckSet practice) {
        s.setStep(JpathSkillStep.DONE);
        s.setCompletedAt(clock.now());
        if (practice != null) {
            s.setScorePct(practice.getScorePct());
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

    private Optional<JpathCheckSet> submitted(Long userId, String token) {
        if (token == null) {
            return Optional.empty();
        }
        return checks.findByTokenAndUserId(token, userId).filter(c -> SUBMITTED.equals(c.getStatus()));
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
        Integer practiceTotal = total(userId, s.getPracticeToken());
        Integer dictationTotal = total(userId, s.getDictationToken());
        int questions = practiceTotal != null ? practiceTotal : reader.visibleQuestions(codes).size();
        int dictation = dictationTotal != null ? dictationTotal
                : s.getSkill() == JpathSkill.LISTENING ? JpathSkillPolicy.DICTATION_FLOOR : 0;
        return new JpathSkillSessionView(s.getId(), s.getSkill(), s.getKind(), s.getTypeCode(), s.getStep(),
                codes.size(), questions, dictation, s.isReused(), s.getPracticeToken(), s.getDictationToken(),
                s.getCompletedAt() != null, s.getCpAwarded() == null ? 0 : s.getCpAwarded());
    }

    private Integer total(Long userId, String token) {
        return token == null ? null
                : checks.findByTokenAndUserId(token, userId).map(JpathCheckSet::getTotalCount).orElse(null);
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
