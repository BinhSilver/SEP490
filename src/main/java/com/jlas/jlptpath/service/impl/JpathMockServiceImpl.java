package com.jlas.jlptpath.service.impl;

import com.jlas.assessment.dto.JlptExamResponse;
import com.jlas.assessment.dto.JlptMockAttemptReviewResponse;
import com.jlas.assessment.dto.JlptMockResultResponse;
import com.jlas.assessment.service.JlptMockAttemptService;
import com.jlas.assessment.service.JlptService;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathMockStatus;
import com.jlas.jlptpath.entity.JpathMockResult;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserLevelId;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathMockOutcome;
import com.jlas.jlptpath.enums.JpathMockResultState;
import com.jlas.jlptpath.enums.JpathMockSection;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.enums.JpathStage;
import com.jlas.jlptpath.repository.JpathMockResultRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathLevelUpService;
import com.jlas.jlptpath.service.JpathMockService;
import com.jlas.jlptpath.util.JpathMockSections;
import com.jlas.learning.dto.CompleteFromMockRequest;
import com.jlas.learning.dto.MockTestDestination;
import com.jlas.learning.service.MockTestDestinationProvider;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * [JLAS-W0108] Plan 3 Task 17 — bridge to the Mock team's JLPT mock test. Uses only the Mock team's public beans
 * ({@link MockTestDestinationProvider}, {@link JlptMockAttemptService#latest}/{@link JlptMockAttemptService#review},
 * {@link JlptService#getExam} for part titles); never reads jlpt_* tables itself and never trusts a client pass flag.
 *
 * <p>Ruling (Plan 3 Task 1): the destination resolves AVAILABLE (/jlpt?level=L) but nothing writes jlpt_mock_attempt
 * yet, so {@code latest()} is empty and the card shows the live link with "Kết quả Thi thử chưa được nối — lên cấp chưa
 * tự động". As soon as attempt rows exist the same code shows "Có kết quả mới — Cập nhật" and applies them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JpathMockServiceImpl implements JpathMockService {

    static final String NOTICE_UNAVAILABLE = "Thi thử JLPT chưa sẵn sàng.";
    static final String NOTICE_NOT_CONNECTED = "Kết quả Thi thử chưa được nối — lên cấp chưa tự động";
    static final String NOTICE_PENDING = "Có kết quả mới — Cập nhật";
    static final String NOTICE_PASSED = "Bạn đã đậu Thi thử cấp này.";
    static final String NOTICE_FAILED = "Chưa đạt lần này — xem gợi ý theo phần và thi thử lại sau 24 giờ.";
    /** #42: "Thi thử lại" opens 24 hours after the failed attempt (a path button only; exam rules belong to Mock). */
    static final long RETAKE_HOURS = 24;

    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository levels;
    private final JpathMockResultRepository results;
    private final MockTestDestinationProvider destinations;
    private final JlptMockAttemptService attempts;
    private final JlptService exams;
    /** Task 18 provides the implementation; until then complete/sync report JPATH_MOCK_UNAVAILABLE. */
    private final ObjectProvider<JpathLevelUpService> levelUp;

    @Override
    public JpathMockStatus status(Long userId, String level) {
        JpathUserPath path = activePath(userId);
        String lvl = level == null || level.isBlank() ? path.getOpenLevel() : level.trim().toUpperCase(Locale.ROOT);
        JpathUserLevel userLevel = userLevel(path, lvl);
        return build(userId, path, userLevel, attempts.latest(userId, lvl), null);
    }

    @Override
    public JpathMockStatus complete(Long userId, CompleteFromMockRequest request) {
        JpathUserPath path = activePath(userId);
        JlptMockAttemptReviewResponse review;
        try {
            review = attempts.review(userId, request.attemptId());
        } catch (ApiException ex) {
            if (ex.getErrorCode() == ErrorCode.NOT_FOUND) {
                throw new ApiException(ErrorCode.JPATH_MOCK_ATTEMPT_NOT_FOUND);
            }
            throw ex;
        }
        JlptMockResultResponse result = review.result();
        if (result == null) {
            throw new ApiException(ErrorCode.JPATH_MOCK_ATTEMPT_NOT_FOUND);
        }
        if (result.level() == null || !result.level().equalsIgnoreCase(path.getOpenLevel())) {
            throw new ApiException(ErrorCode.JPATH_MOCK_NOT_APPLICABLE);
        }
        JpathUserLevel userLevel = userLevel(path, path.getOpenLevel());
        if (!applicable(result, path, userLevel)) {
            throw new ApiException(ErrorCode.JPATH_MOCK_NOT_APPLICABLE);
        }
        if (!results.existsById(result.attemptId())) {
            JpathLevelUpService applier = levelUp.getIfAvailable();
            if (applier == null) {
                throw new ApiException(ErrorCode.JPATH_MOCK_UNAVAILABLE);
            }
            applier.apply(userId, result);
        }
        JpathUserPath after = activePath(userId);
        return build(userId, after, userLevel(after, userLevel.getLevel()), result, review.exam());
    }

    @Override
    public JpathMockStatus sync(Long userId, String level) {
        JpathUserPath path = activePath(userId);
        String lvl = level == null || level.isBlank() ? path.getOpenLevel() : level.trim().toUpperCase(Locale.ROOT);
        JpathUserLevel userLevel = userLevel(path, lvl);
        JlptMockResultResponse latest = attempts.latest(userId, lvl);
        if (latest == null || !applicable(latest, path, userLevel) || results.existsById(latest.attemptId())) {
            return build(userId, path, userLevel, latest, null);
        }
        return complete(userId, new CompleteFromMockRequest(latest.attemptId()));
    }

    // ---- helpers ----

    private JpathMockStatus build(Long userId, JpathUserPath path, JpathUserLevel userLevel,
            JlptMockResultResponse latest, JlptExamResponse exam) {
        String level = userLevel.getLevel();
        MockTestDestination destination = destinations.resolveDestination(level);
        boolean levelPassed = userLevel.getStage() == JpathStage.PASSED;
        String nextLevel = levelPassed && !level.equalsIgnoreCase(path.getOpenLevel()) ? path.getOpenLevel() : null;

        JpathMockResult applied = latest == null ? null : results.findById(latest.attemptId()).orElse(null);
        JpathMockResultState state;
        if (applied != null) {
            state = JpathMockResultState.APPLIED;
        } else if (latest != null && applicable(latest, path, userLevel)) {
            state = JpathMockResultState.PENDING;
        } else {
            state = JpathMockResultState.NONE;
        }
        if (state == JpathMockResultState.NONE) {
            String notice = destination.available() ? NOTICE_NOT_CONNECTED : NOTICE_UNAVAILABLE;
            return new JpathMockStatus(level, destination, state, notice, null, null, null, List.of(), null,
                    List.of(), levelPassed, nextLevel);
        }

        JpathMockOutcome outcome = applied != null ? applied.getOutcome()
                : latest.passed() ? JpathMockOutcome.PASSED : JpathMockOutcome.FAILED;
        Integer score = applied != null ? applied.getScorePct() : latest.scorePercent();
        LocalDateTime submittedAt = applied != null ? applied.getSubmittedAt() : latest.submittedAt();
        boolean failed = outcome == JpathMockOutcome.FAILED;
        List<JpathMockStatus.SectionView> sections = sections(latest, exam, failed);
        List<JpathMockStatus.Advice> advice = failed ? advice(sections) : List.of();
        LocalDateTime retakeAt = failed && submittedAt != null ? submittedAt.plusHours(RETAKE_HOURS) : null;
        String notice;
        if (!destination.available()) {
            notice = NOTICE_UNAVAILABLE;
        } else if (state == JpathMockResultState.PENDING) {
            notice = NOTICE_PENDING;
        } else {
            notice = failed ? NOTICE_FAILED : NOTICE_PASSED;
        }
        return new JpathMockStatus(level, destination, state, notice, latest.attemptId(), outcome, score, sections,
                retakeAt, advice, levelPassed, nextLevel);
    }

    /** #42: the attempt's level is the path's open level and it was submitted at or after the level opened. */
    private static boolean applicable(JlptMockResultResponse r, JpathUserPath path, JpathUserLevel userLevel) {
        return r.level() != null && r.level().equalsIgnoreCase(path.getOpenLevel())
                && userLevel.getLevel().equalsIgnoreCase(path.getOpenLevel())
                && r.submittedAt() != null && userLevel.getOpenedAt() != null
                && !r.submittedAt().isBefore(userLevel.getOpenedAt());
    }

    private List<JpathMockStatus.SectionView> sections(JlptMockResultResponse r, JlptExamResponse exam, boolean failed) {
        Map<String, JlptMockResultResponse.SectionResult> raw = r.sections();
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Map<String, String> titles = titles(exam != null ? exam : examOrNull(r.examCode()));
        int lowest = raw.values().stream().mapToInt(JlptMockResultResponse.SectionResult::scorePercent).min().orElse(0);
        List<JpathMockStatus.SectionView> out = new ArrayList<>();
        raw.forEach((code, s) -> {
            boolean weak = failed && (!s.passed() || s.scorePercent() == lowest);
            out.add(new JpathMockStatus.SectionView(code, titles.getOrDefault(code, code), JpathMockSections.of(code),
                    s.scorePercent(), s.passed(), weak));
        });
        return List.copyOf(out);
    }

    private static List<JpathMockStatus.Advice> advice(List<JpathMockStatus.SectionView> sections) {
        Set<JpathMockSection> weak = new LinkedHashSet<>();
        for (JpathMockStatus.SectionView s : sections) {
            if (s.weak()) {
                weak.add(s.section());
            }
        }
        List<JpathMockStatus.Advice> out = new ArrayList<>();
        for (JpathMockSection section : weak) {
            for (JpathMockSections.Hint hint : JpathMockSections.advice(section)) {
                out.add(new JpathMockStatus.Advice(section, hint.action(), hint.labelVi()));
            }
        }
        return List.copyOf(out);
    }

    /** Part titles are display only: a missing or unreadable exam falls back to the part code. */
    private JlptExamResponse examOrNull(String examCode) {
        if (examCode == null) {
            return null;
        }
        try {
            return exams.getExam(examCode);
        } catch (RuntimeException ex) {
            log.debug("[jpath-mock] exam {} not readable for part titles: {}", examCode, ex.getMessage());
            return null;
        }
    }

    private static Map<String, String> titles(JlptExamResponse exam) {
        Map<String, String> titles = new HashMap<>();
        if (exam != null && exam.parts() != null) {
            for (JlptExamResponse.Part part : exam.parts()) {
                if (part.code() != null && part.title() != null && !part.title().isBlank()) {
                    titles.put(part.code(), part.title());
                }
            }
        }
        return titles;
    }

    private JpathUserPath activePath(Long userId) {
        return paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
    }

    private JpathUserLevel userLevel(JpathUserPath path, String level) {
        return levels.findById(new JpathUserLevelId(path.getId(), level))
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_MOCK_NOT_APPLICABLE));
    }
}
