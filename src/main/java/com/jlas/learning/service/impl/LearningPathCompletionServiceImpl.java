package com.jlas.learning.service.impl;

import com.jlas.common.event.LearningEvent;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.PathCompletionResponse;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.repository.UserLearningStageRepository;
import com.jlas.learning.service.JlptQualificationPort;
import com.jlas.learning.service.LearningFinalEligibilityService;
import com.jlas.learning.service.LearningPathCompletionService;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** [JLAS-W0108] Task 9 — server-owned completion: lock the ACTIVE path, re-derive eligibility from package
 * counts, verify the Mock Test attempt through {@link JlptQualificationPort}, then transition exactly once.
 * The request carries only an attempt id. A retry finds no ACTIVE path and reports the completed state
 * without touching the port or publishing a second event. */
@Service
public class LearningPathCompletionServiceImpl implements LearningPathCompletionService {
    private static final Map<String, String> REASON_TEXT = Map.of(
            "ATTEMPT_NOT_FOUND", "Không tìm thấy bài thi của bạn.",
            "ATTEMPT_NOT_JLPT", "Bài này không phải bài thi JLPT.",
            "ATTEMPT_NOT_SUBMITTED", "Bài thi chưa được nộp.",
            "LEVEL_MISMATCH", "Bài thi không đúng cấp độ mục tiêu của lộ trình.",
            "NO_SECTION_RESULTS", "Bài thi chưa có kết quả từng phần.",
            "SECTION_BELOW_PASSING", "Có phần thi chưa đạt điểm tối thiểu.",
            "TOTAL_SCORE_BELOW_PASSING", "Tổng điểm bài thi chưa đạt.",
            "POLICY_VERSION_MISSING", "Kết quả bài thi chưa có phiên bản chính sách hợp lệ.",
            "POLICY_VERSION_MISMATCH", "Kết quả bài thi thuộc phiên bản chính sách cũ, hãy thi lại.");

    private final UserLearningPathRepository paths;
    private final LearningFinalEligibilityService eligibility;
    private final UserLearningStageRepository stages;
    private final JlptQualificationPort qualification;
    private final ApplicationEventPublisher events;

    public LearningPathCompletionServiceImpl(UserLearningPathRepository paths,
            LearningFinalEligibilityService eligibility, UserLearningStageRepository stages,
            JlptQualificationPort qualification, ApplicationEventPublisher events) {
        this.paths = paths;
        this.eligibility = eligibility;
        this.stages = stages;
        this.qualification = qualification;
        this.events = events;
    }

    @Override
    @Transactional
    public PathCompletionResponse completeFromMock(Long userId, Long attemptId) {
        UserLearningPath active = paths.lockActiveInteractive(userId).orElse(null);
        if (active == null) {
            var latest = paths.findTopByUserIdAndExperienceVersionOrderByIdDesc(
                    userId, LearningExperienceVersion.INTERACTIVE_V2).orElse(null);
            if (latest != null && "COMPLETED".equals(latest.getStatus())) {
                // Replay only for the attempt that qualified the path; any other id must not learn it succeeded.
                if (attemptId != null && attemptId.equals(latest.getCompletionAttemptId())) {
                    return new PathCompletionResponse(latest.getId(), "COMPLETED", false,
                            latest.getTargetLevel(), latest.getCompletedAt());
                }
                throw new ApiException(ErrorCode.CONFLICT,
                        "Lộ trình đã hoàn thành bằng một bài thi khác.");
            }
            throw new ApiException(ErrorCode.NOT_FOUND, "Bạn chưa có lộ trình tương tác đang hoạt động.");
        }

        var decision = eligibility.forPath(active.getId());
        if (!decision.eligible()) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET,
                    "Bạn cần hoàn thành thêm nội dung lộ trình trước khi thi thử.");
        }
        var verdict = qualification.verify(userId, attemptId, decision.targetLevel());
        if (!verdict.qualified()) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET, REASON_TEXT.getOrDefault(
                    verdict.reasonCode(), "Bài thi chưa đủ điều kiện hoàn thành lộ trình."));
        }

        LocalDateTime now = LocalDateTime.now();
        active.setStatus("COMPLETED");
        active.setCompletedAt(now);
        active.setCompletionAttemptId(attemptId);
        paths.save(active);
        stages.findByLearningPathIdOrderByStageIndexAsc(active.getId()).forEach(stage -> {
            if (!"COMPLETED".equals(stage.getStatus())) {
                stage.setStatus("COMPLETED");
                stages.save(stage);
            }
        });
        events.publishEvent(LearningEvent.pathCompleted(userId, active.getId()));
        return new PathCompletionResponse(active.getId(), "COMPLETED", true, decision.targetLevel(), now);
    }
}
