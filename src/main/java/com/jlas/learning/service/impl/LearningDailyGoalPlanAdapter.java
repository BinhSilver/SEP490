package com.jlas.learning.service.impl;

import com.jlas.learning.enums.*;
import com.jlas.learning.repository.*;
import com.jlas.mission.service.DailyGoalPlanPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Projects the first actionable package; it never changes progress or unlock state. */
@Component
@RequiredArgsConstructor
public class LearningDailyGoalPlanAdapter implements DailyGoalPlanPort {
    private final UserLearningPathRepository paths;
    private final LearningPathPackageRepository packages;
    private final LearningActivityMemberRepository members;

    @Override
    @Transactional(readOnly = true)
    public Optional<Targets> resolve(Long userId) {
        var path = paths.findByUserIdAndStatusAndExperienceVersion(
                userId, "ACTIVE", LearningExperienceVersion.INTERACTIVE_V2).orElse(null);
        if (path == null) return Optional.empty();
        var actionable = packages.findByLearningPathIdOrderByOrderIndex(path.getId()).stream()
                .filter(row -> row.getStatus() == LearningPackageStatus.AVAILABLE
                        || row.getStatus() == LearningPackageStatus.IN_PROGRESS)
                .findFirst().orElse(null);
        if (actionable == null) return Optional.empty();
        long vocabulary = members.countByActivityLearningPackageIdAndMemberType(
                actionable.getId(), LearningMemberType.VOCABULARY_ENTRY);
        return Optional.of(new Targets((int) Math.min(Integer.MAX_VALUE, vocabulary), 1,
                Math.max(1, actionable.getEstimatedMinutes()), "ACTIVE_PATH"));
    }
}
