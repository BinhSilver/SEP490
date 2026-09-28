package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.InteractiveLearningActivityResponse;
import com.jlas.learning.dto.InteractiveLearningPackageResponse;
import com.jlas.learning.dto.InteractiveLearningPathResponse;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningPackageStatus;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.service.InteractiveLearningPathService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InteractiveLearningPathServiceImpl implements InteractiveLearningPathService {
    private static final EnumSet<LearningActivityState> DONE =
            EnumSet.of(LearningActivityState.PASSED, LearningActivityState.COMPLETED);
    private static final EnumSet<LearningActivityType> TARGET_TYPES =
            EnumSet.allOf(LearningActivityType.class);

    private final UserLearningPathRepository paths;
    private final LearningPathPackageRepository packages;
    private final UserLearningPathItemRepository items;

    @Override
    @Transactional(readOnly = true)
    public InteractiveLearningPathResponse current(Long userId) {
        UserLearningPath path = paths.findByUserIdAndStatusAndExperienceVersion(
                        userId, "ACTIVE", LearningExperienceVersion.INTERACTIVE_V2)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Bạn chưa có lộ trình tương tác đang hoạt động."));
        List<InteractiveLearningPackageResponse> packageViews = packages
                .findByLearningPathIdOrderByOrderIndex(path.getId()).stream()
                .map(this::mapPackage)
                .toList();
        int completed = (int) packageViews.stream()
                .filter(view -> view.status() == LearningPackageStatus.COMPLETED).count();
        Long currentId = packageViews.stream()
                .filter(view -> view.status() == LearningPackageStatus.IN_PROGRESS)
                .map(InteractiveLearningPackageResponse::id).findFirst()
                .orElseGet(() -> packageViews.stream()
                        .filter(view -> view.status() == LearningPackageStatus.AVAILABLE)
                        .map(InteractiveLearningPackageResponse::id).findFirst().orElse(null));
        return new InteractiveLearningPathResponse(path.getId(), path.getTitle(), path.getGoal(),
                path.getCurrentLevel(), path.getTargetLevel(), path.getMinutesPerDay(), completed,
                packageViews.size(), percent(completed, packageViews.size()), currentId, packageViews);
    }

    @Override
    @Transactional(readOnly = true)
    public InteractiveLearningPackageResponse packageDetail(Long userId, Long packageId) {
        LearningPathPackage learningPackage = packages.findOwnedById(packageId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        requireInteractive(learningPackage);
        return mapPackage(learningPackage);
    }

    @Override
    @Transactional
    public InteractiveLearningPackageResponse start(Long userId, Long packageId) {
        LearningPathPackage learningPackage = packages.findOwnedByIdForUpdate(packageId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        requireInteractive(learningPackage);
        if (learningPackage.getStatus() == LearningPackageStatus.LOCKED) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET,
                    "Hãy hoàn thành package trước để mở ngày học này.");
        }
        List<UserLearningPathItem> activities = orderedActivities(learningPackage);
        validateTargetPackage(learningPackage, activities);
        if (learningPackage.getStatus() == LearningPackageStatus.AVAILABLE) {
            learningPackage.setStatus(LearningPackageStatus.IN_PROGRESS);
            learningPackage.setStartedAt(LocalDateTime.now());
            UserLearningPathItem first = activities.getFirst();
            if (first.getActivityState() == LearningActivityState.LOCKED) {
                first.setActivityState(LearningActivityState.STUDYING);
            }
        }
        return mapPackage(learningPackage, activities);
    }

    private InteractiveLearningPackageResponse mapPackage(LearningPathPackage learningPackage) {
        return mapPackage(learningPackage, orderedActivities(learningPackage));
    }

    private List<UserLearningPathItem> orderedActivities(LearningPathPackage learningPackage) {
        List<UserLearningPathItem> activities = items
                .findByLearningPackageIdOrderByOrderIndex(learningPackage.getId());
        validateTargetPackage(learningPackage, activities);
        return activities;
    }

    private void validateTargetPackage(LearningPathPackage learningPackage, List<UserLearningPathItem> activities) {
        if (!"GOAL_LEARNING".equals(learningPackage.getPhase())) return;
        if (learningPackage.getSourceLesson() == null || learningPackage.getContentRevision() == null
                || learningPackage.getContentRevision().isBlank()) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                    "Package chưa có snapshot nguồn canonical hợp lệ.");
        }
        EnumSet<LearningActivityType> actual = EnumSet.noneOf(LearningActivityType.class);
        activities.forEach(item -> {
            if (item.getActivityType() != null) actual.add(item.getActivityType());
        });
        boolean missingState = activities.stream().anyMatch(item -> item.getActivityState() == null);
        if (activities.size() != 4 || !actual.equals(TARGET_TYPES) || missingState) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                    "Package chưa đủ Từ vựng, Kanji, Ngữ pháp và Hội thoại.");
        }
    }

    private void requireInteractive(LearningPathPackage learningPackage) {
        if (learningPackage.getLearningPath().getExperienceVersion() != LearningExperienceVersion.INTERACTIVE_V2) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }

    private InteractiveLearningPackageResponse mapPackage(
            LearningPathPackage learningPackage, List<UserLearningPathItem> activities) {
        List<InteractiveLearningActivityResponse> activityViews = activities.stream()
                .map(item -> new InteractiveLearningActivityResponse(item.getId(), item.getActivityType(),
                        item.getActivityState(), item.getOrderIndex()))
                .toList();
        int completed = (int) activityViews.stream()
                .filter(view -> view.state() != null && DONE.contains(view.state())).count();
        LearningActivityType resume = activityViews.stream()
                .filter(view -> view.state() != null && !DONE.contains(view.state()))
                .map(InteractiveLearningActivityResponse::type).findFirst().orElse(null);
        String lockReason = learningPackage.getStatus() == LearningPackageStatus.LOCKED
                ? "Hoàn thành ngày học trước để mở khóa." : null;
        return new InteractiveLearningPackageResponse(learningPackage.getId(), learningPackage.getPhase(),
                learningPackage.getDayNumber(), learningPackage.getOrderIndex(), learningPackage.getTitle(),
                learningPackage.getStatus(), learningPackage.getEstimatedMinutes(), completed,
                activityViews.size(), percent(completed, activityViews.size()), resume, lockReason,
                learningPackage.getStartedAt(), learningPackage.getCompletedAt(), activityViews);
    }

    private int percent(int completed, int total) {
        return total == 0 ? 0 : Math.round(completed * 100f / total);
    }
}
