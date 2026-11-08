package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.assessment.dto.JlptMockResultResponse;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathLevelSummary;
import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathMockResult;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserLevelId;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathMockOutcome;
import com.jlas.jlptpath.enums.JpathMockSection;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.enums.JpathSliderKind;
import com.jlas.jlptpath.enums.JpathStage;
import com.jlas.jlptpath.repository.JpathMockResultRepository;
import com.jlas.jlptpath.repository.JpathSliderAdjustmentRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathDailyPlanService;
import com.jlas.jlptpath.service.JpathLevelUpService;
import com.jlas.jlptpath.service.JpathRewardHook;
import com.jlas.jlptpath.service.JpathTodayTargetsPort;
import com.jlas.jlptpath.util.JpathMockSections;
import com.jlas.jlptpath.util.JpathPacingEngine;
import com.jlas.jlptpath.util.JpathRewardPolicy;
import com.jlas.mission.service.CreditAwardService;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [JLAS-W0108] Plan 3 Task 18 — level-up by the JLPT mock test. {@link #apply} runs {@code ensurePlanExists} outside the
 * transaction, then one writer transaction: path {@code findActiveForUpdate} → {@code lockToday} (level, plan) → level
 * {@code findForUpdate}. The attempt id is the key of jpath_mock_result, and the check for it runs under the path lock,
 * so two parallel applies of one attempt apply it once.
 */
@Slf4j
@Service
public class JpathLevelUpServiceImpl implements JpathLevelUpService {

    static final List<String> LEVELS = List.of("N5", "N4", "N3", "N2", "N1");
    /** Mock submittedAt is server time (JlptMockAttemptService uses LocalDateTime.now()). */
    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository levels;
    private final JpathMockResultRepository results;
    private final JpathSliderAdjustmentRepository adjustments;
    private final JpathDailyPlanService dailyPlans;
    private final JpathCatalogService catalog;
    private final JpathClock clock;
    private final JpathLevelSummaryReader summaries;
    private final ObjectProvider<JpathRewardHook> hooks;
    private final ObjectProvider<CreditAwardService> awards;
    private final ObjectProvider<JpathTodayTargetsPort> todayTargets;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final ObjectMapper json = new ObjectMapper();

    public JpathLevelUpServiceImpl(JpathUserPathRepository paths, JpathUserLevelRepository levels,
            JpathMockResultRepository results, JpathSliderAdjustmentRepository adjustments,
            JpathDailyPlanService dailyPlans, JpathCatalogService catalog, JpathClock clock,
            JpathLevelSummaryReader summaries, ObjectProvider<JpathRewardHook> hooks,
            ObjectProvider<CreditAwardService> awards, ObjectProvider<JpathTodayTargetsPort> todayTargets,
            PlatformTransactionManager txManager) {
        this.paths = paths;
        this.levels = levels;
        this.results = results;
        this.adjustments = adjustments;
        this.dailyPlans = dailyPlans;
        this.catalog = catalog;
        this.clock = clock;
        this.summaries = summaries;
        this.hooks = hooks;
        this.awards = awards;
        this.todayTargets = todayTargets;
        this.tx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    @Override
    public JpathMockResult apply(Long userId, JlptMockResultResponse r) {
        dailyPlans.ensurePlanExists(userId);
        return tx.execute(status -> applyLocked(userId, r));
    }

    private JpathMockResult applyLocked(Long userId, JlptMockResultResponse r) {
        JpathUserPath path = paths.findActiveForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
        JpathDailyPlan plan = dailyPlans.lockToday(path);
        String level = r.level() == null ? null : r.level().toUpperCase(Locale.ROOT);
        JpathUserLevel userLevel = levels.findForUpdate(path.getId(), level)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_MOCK_NOT_APPLICABLE));

        Optional<JpathMockResult> existing = results.findById(r.attemptId());
        if (existing.isPresent()) {
            return existing.get();
        }
        boolean passed = r.passed();
        boolean firstPass = passed && userLevel.getStage() != JpathStage.PASSED;
        JpathMockResult row = results.save(JpathMockResult.builder()
                .mockAttemptId(r.attemptId())
                .userId(userId)
                .pathId(path.getId())
                .level(level)
                .outcome(passed ? JpathMockOutcome.PASSED : JpathMockOutcome.FAILED)
                .scorePct(r.scorePercent())
                .sectionsJson(sectionsJson(r))
                .submittedAt(r.submittedAt())
                .appliedAt(clock.now())
                .cpAwarded(firstPass ? JpathRewardPolicy.MOCK_PASS : 0)
                .build());

        JpathRewardHook hook = hooks.getIfAvailable();
        markMockDay(userId, plan, r, hook);

        if (firstPass) {
            userLevel.setStage(JpathStage.PASSED);
            userLevel.setPassedAt(clock.now());
            userLevel.setMockAttemptId(r.attemptId());
            openNextLevel(path, level);
            CreditAwardService credit = awards.getIfAvailable();
            if (credit != null) {
                credit.enqueue(userId, JpathRewardPolicy.MOCK_PASS, CreditReason.STUDY_REWARD,
                        JpathRewardPolicy.REF_MOCK_PASS, r.attemptId(), "Đậu Thi thử JLPT " + level, null, null, null);
                applyAwardsAfterCommit(credit, userId);
            }
            paths.flush();
            if (hook != null) {
                hook.onProgressChanged(userId); // grants MOCK_PASS_<L> (+5 CP, #24)
            }
            JpathPathRules.refreshToday(todayTargets, userId);
        }
        return row;
    }

    /** #42: a mock submitted on the learner's today marks today done + streak once (no day CP: "không CP riêng"). */
    private void markMockDay(Long userId, JpathDailyPlan plan, JlptMockResultResponse r, JpathRewardHook hook) {
        if (plan == null || r.submittedAt() == null || plan.getAllDoneAt() != null) {
            return;
        }
        LocalDate today = clock.today(userId);
        LocalDate submittedDay = r.submittedAt().atZone(SERVER_ZONE).withZoneSameInstant(clock.zone(userId))
                .toLocalDate();
        if (!today.equals(submittedDay) || !today.equals(plan.getStudyDay())) {
            return;
        }
        plan.setAllDoneAt(clock.now());
        if (hook != null) {
            hook.onDayCompleted(userId, today, plan.getDayKind(), false);
        }
    }

    /** #45: the next level, when released and the target is not reached yet, becomes the open level (STUDY). */
    private void openNextLevel(JpathUserPath path, String level) {
        String next = nextReleased(level);
        if (next == null || level.equals(path.getTargetLevel())) {
            return;
        }
        JpathUserLevel row = levels.findForUpdate(path.getId(), next).orElse(null);
        if (row == null) {
            JpathSliderValues recommended = JpathPacingEngine.recommend(next, path.getDailyMinutes());
            levels.save(JpathUserLevel.builder()
                    .pathId(path.getId())
                    .level(next)
                    .stage(JpathStage.STUDY)
                    .sliderVocab(recommended.vocab())
                    .sliderKanji(recommended.kanji())
                    .sliderGrammar(recommended.grammar())
                    .adjustmentsUsed(0)
                    .firstDragUsed(false)
                    .openedAt(clock.now())
                    .build());
            adjustments.save(JpathPathRules.adjustment(path.getId(), next, JpathSliderKind.LEVEL_START, false, null,
                    recommended, path.getDailyMinutes()));
        }
        path.setOpenLevel(next);
    }

    private String nextReleased(String level) {
        int index = LEVELS.indexOf(level);
        if (index < 0 || index + 1 >= LEVELS.size()) {
            return null;
        }
        String next = LEVELS.get(index + 1);
        return catalog.isReleased(next) ? next : null;
    }

    private String sectionsJson(JlptMockResultResponse r) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (r.sections() != null) {
            r.sections().forEach((code, s) -> {
                JpathMockSection section = JpathMockSections.of(code);
                out.putIfAbsent(section.name(), Map.of("pct", s.scorePercent(), "passed", s.passed()));
            });
        }
        try {
            return json.writeValueAsString(out);
        } catch (JsonProcessingException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private static void applyAwardsAfterCommit(CreditAwardService credit, Long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    credit.applyOpenAwards(userId);
                } catch (RuntimeException ex) {
                    log.error("Could not apply open credit awards for user {}", userId, ex);
                }
            }
        });
    }

    // ---- summary / certificate ----

    @Override
    public JpathLevelSummary summary(Long userId, String level) {
        return readTx.execute(status -> {
            JpathUserPath path = activePath(userId);
            String lvl = normalize(level);
            Optional<JpathUserLevel> found = levels.findById(new JpathUserLevelId(path.getId(), lvl));
            if (found.isEmpty()) {
                // E2E-006: a level below the open level was passed by the placement (Smart Test) and has no row;
                // the path shows it as "Đã vượt qua", so its summary answers instead of 404.
                if (LEVELS.indexOf(lvl) >= LEVELS.indexOf(path.getOpenLevel())) {
                    throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy cấp này trong lộ trình.");
                }
                JpathLevelSummaryReader.Counts c = summaries.read(userId, lvl);
                return new JpathLevelSummary(lvl, c.known(), c.learned(), c.accuracyPct(), c.studyDays(), c.weak(),
                        null, null, path.getOpenLevel(), false, false, List.of());
            }
            JpathUserLevel row = found.get();
            boolean passed = row.getStage() == JpathStage.PASSED;
            Integer mockScore = row.getMockAttemptId() == null ? null
                    : results.findById(row.getMockAttemptId()).map(JpathMockResult::getScorePct).orElse(null);
            String nextLevel = passed && !lvl.equals(path.getOpenLevel()) ? path.getOpenLevel() : null;
            boolean targetReached = passed && lvl.equals(path.getTargetLevel());
            boolean nextComing = passed && nextLevel == null && !targetReached && !"N1".equals(lvl);
            JpathLevelSummaryReader.Counts c = summaries.read(userId, lvl);
            return new JpathLevelSummary(lvl, c.known(), c.learned(), c.accuracyPct(), c.studyDays(), c.weak(),
                    mockScore, row.getPassedAt() == null ? null : row.getPassedAt().toLocalDate(), nextLevel,
                    targetReached, nextComing, List.of());
        });
    }

    @Override
    public Optional<LocalDateTime> passedAt(Long userId, String level) {
        return paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE)
                .flatMap(p -> levels.findById(new JpathUserLevelId(p.getId(), normalize(level))))
                .filter(l -> l.getStage() == JpathStage.PASSED && l.getPassedAt() != null)
                .map(JpathUserLevel::getPassedAt);
    }

    private JpathUserPath activePath(Long userId) {
        return paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
    }

    private static String normalize(String level) {
        String key = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(key)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy cấp này trong lộ trình.");
        }
        return key;
    }
}
