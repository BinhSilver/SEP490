package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.FinalEligibilityResponse;
import com.jlas.learning.dto.LearningStageProgressResponse;
import com.jlas.learning.entity.UserLearningStage;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningPackageStatus;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.repository.UserLearningStageRepository;
import com.jlas.learning.service.LearningFinalEligibilityService;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** [JLAS-W0108] Task 9 — early Mock Test opens only when every prior stage is 100% and the target (last) stage
 * is at least {@value #TARGET_THRESHOLD_PERCENT}%. Progress is the floored share of required packages, never
 * a rounded or client-supplied figure; a stage with no required package is BLOCKED_CONTENT, not complete. */
@Service
public class LearningFinalEligibilityServiceImpl implements LearningFinalEligibilityService {
    static final int TARGET_THRESHOLD_PERCENT = 50;

    /** Required-package counts of one stage (test seam and repository projection target). */
    public record StageCount(Long stageId, int stageIndex, String level, String status,
            int completed, int total) {
    }

    private final UserLearningPathRepository paths;
    private final LearningPathPackageRepository packages;
    private final UserLearningStageRepository stages;

    public LearningFinalEligibilityServiceImpl(UserLearningPathRepository paths,
            LearningPathPackageRepository packages, UserLearningStageRepository stages) {
        this.paths = paths;
        this.packages = packages;
        this.stages = stages;
    }

    @Override
    @Transactional(readOnly = true)
    public FinalEligibilityResponse forUser(Long userId) {
        var path = paths.findByUserIdAndStatusAndExperienceVersion(userId, "ACTIVE",
                LearningExperienceVersion.INTERACTIVE_V2)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Bạn chưa có lộ trình tương tác đang hoạt động."));
        return forPath(path.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public FinalEligibilityResponse forPath(Long pathId) {
        return evaluate(countStages(pathId));
    }

    /** Counts required packages per stage of one path. Reused by completion so both share one definition. */
    private List<StageCount> countStages(Long pathId) {
        Map<Long, int[]> counts = new HashMap<>();
        for (var pkg : packages.findByLearningPathIdOrderByOrderIndex(pathId)) {
            if (pkg.getStageId() == null) continue;
            int[] c = counts.computeIfAbsent(pkg.getStageId(), k -> new int[2]);
            c[1]++;
            if (pkg.getStatus() == LearningPackageStatus.COMPLETED) c[0]++;
        }
        return stages.findByLearningPathIdOrderByStageIndexAsc(pathId).stream().map((UserLearningStage s) -> {
            int[] c = counts.getOrDefault(s.getId(), new int[2]);
            return new StageCount(s.getId(), s.getStageIndex(), s.getLevel(), s.getStatus(), c[0], c[1]);
        }).toList();
    }

    public static FinalEligibilityResponse evaluate(List<StageCount> input) {
        List<StageCount> ordered = input.stream().sorted(Comparator.comparingInt(StageCount::stageIndex)).toList();
        List<LearningStageProgressResponse> progress = ordered.stream().map(s -> new LearningStageProgressResponse(
                s.stageId(), s.stageIndex(), s.level(), s.status(), s.completed(), s.total(),
                s.total() <= 0 ? 0 : (int) Math.floor(s.completed() * 100.0 / s.total()), s.total() <= 0)).toList();
        if (progress.isEmpty()) return blocked(null, progress, "BLOCKED_CONTENT");
        String target = progress.get(progress.size() - 1).level();
        if (progress.stream().anyMatch(LearningStageProgressResponse::blocked)) {
            return blocked(target, progress, "BLOCKED_CONTENT");
        }
        for (int i = 0; i < progress.size() - 1; i++) {
            if (progress.get(i).completedPackages() != progress.get(i).totalPackages()) {
                return blocked(target, progress, "PRIOR_STAGE_INCOMPLETE");
            }
        }
        if (progress.get(progress.size() - 1).percent() < TARGET_THRESHOLD_PERCENT) {
            return blocked(target, progress, "TARGET_STAGE_BELOW_THRESHOLD");
        }
        return new FinalEligibilityResponse(true, target, TARGET_THRESHOLD_PERCENT, progress, null);
    }

    private static FinalEligibilityResponse blocked(String target, List<LearningStageProgressResponse> progress,
            String reason) {
        return new FinalEligibilityResponse(false, target, TARGET_THRESHOLD_PERCENT, progress, reason);
    }
}
