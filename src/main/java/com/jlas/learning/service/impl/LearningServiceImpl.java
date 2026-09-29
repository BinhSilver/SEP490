package com.jlas.learning.service.impl;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.learning.dto.CreateLearningPathResult;
import com.jlas.learning.dto.LearningCatalogScope;
import com.jlas.learning.dto.LearningPathResponse;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.LearningSelectionResult;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.LessonProgressRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.service.LearningProfileSource;
import com.jlas.learning.service.LearningResourceSelectionService;
import com.jlas.learning.service.LearningScheduleService;
import com.jlas.learning.service.LearningScopeSource;
import com.jlas.learning.service.LearningService;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cài đặt {@link LearningService}. */
@Service
@RequiredArgsConstructor
public class LearningServiceImpl implements LearningService {
    private final UserRepository users;
    private final UserLearningPathRepository paths;
    private final UserLearningPathItemRepository items;
    private final LessonProgressRepository progress;
    private final LearningResourceSelectionService selector;
    private final LearningScheduleService scheduler;
    private final LearningMapper mapper;
    private final ObjectProvider<LearningProfileSource> profileSources;
    private final ObjectProvider<LearningScopeSource> scopeSources;
    private final EntityManager em;

    /** Synthetic assessment attempt id used only by the local demo adapter to seed a path on view. */
    private static final long LOCAL_DEMO_ATTEMPT_ID = 1L;

    @Override
    @Transactional
    public CreateLearningPathResult create(Long userId, Long assessmentAttemptId) {
        requirePositiveUserId(userId);
        if (assessmentAttemptId == null || assessmentAttemptId <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A finalized assessment attempt is required.");
        }
        // Lock the user row up front: this validates the user exists (404) and serializes
        // concurrent path creation before version/idempotency decisions. findForCreditUpdate is
        // the auth module's existing pessimistic-write lock on the user row.
        User user = users.findForCreditUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found."));
        LearningProfileSource profileSource = profileSources.getIfAvailable(() -> {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Learning profile source is not configured yet.");
        });
        LearningScopeSource scopeSource = scopeSources.getIfAvailable(() -> {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Curriculum scope source is not configured yet.");
        });
        LearningProfileSnapshot profile = profileSource.loadForUser(userId, assessmentAttemptId);
        if (profile == null || !userId.equals(profile.userId())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        LearningCatalogScope scope = scopeSource.resolve(userId, profile);
        LearningSelectionResult selection = selector.select(userId, profile, scope);
        var schedule = scheduler.schedule(selection.resources(), profile.minutesPerDay());

        // The user-row lock above already serializes concurrent creation for this user.
        var existing = paths.findByUserIdAndAssessmentAttemptIdAndAssessmentRevisionAndPolicyVersion(
                userId, profile.assessmentAttemptId(), profile.assessmentRevision(), selection.policyVersion());
        if (existing.isPresent()) {
            return new CreateLearningPathResult(loadResponse(existing.get()), false);
        }

        int nextVersion = paths.findMaxVersionByUserId(userId) + 1;
        supersedeActive(userId);

        var path = UserLearningPath.builder()
                .user(user)
                .title(buildTitle(profile))
                .goal(profile.goalCode())
                .targetLevel(profile.targetLevel())
                .currentLevel(profile.assessedLevel())
                .surveyId(profile.surveyId())
                .assessmentAttemptId(profile.assessmentAttemptId())
                .assessmentRevision(profile.assessmentRevision())
                .policyVersion(selection.policyVersion())
                .minutesPerDay(profile.minutesPerDay())
                .coverageGapsJson(mapper.writeCoverageGaps(selection.coverageGaps()))
                .status("ACTIVE")
                .version(nextVersion)
                .build();
        paths.save(path);

        List<UserLearningPathItem> persisted = new ArrayList<>();
        for (var scheduled : schedule) {
            persisted.add(UserLearningPathItem.builder()
                    .learningPath(path)
                    .lesson(em.getReference(Lesson.class, scheduled.resource().lessonId()))
                    .orderIndex(scheduled.orderIndex())
                    .phase(scheduled.phase().name())
                    .reasonCode(scheduled.reasonCode())
                    .reason(scheduled.reasonText())
                    .dayNumber(scheduled.dayNumber())
                    .estimatedMinutes(scheduled.estimatedMinutes())
                    .matchedTagCodes(mapper.writeMatchedTags(scheduled.matchedTagCodes()))
                    .build());
        }
        items.saveAll(persisted);

        return new CreateLearningPathResult(loadResponse(path), true);
    }

    @Override
    @Transactional
    public LearningPathResponse current(Long userId) {
        requirePositiveUserId(userId);
        return paths.findByUserIdAndStatus(userId, "ACTIVE")
                .map(this::loadResponse)
                .orElseGet(() -> generateLocalDemoPath(userId));
    }

    /**
     * Demo convenience: on first view, fabricate a ready-made path so the learning-path page
     * shows a real two-phase schedule instead of an empty create state. The local demo adapter
     * is the default profile source until the real survey/Smart Test flow ships.
     */
    private LearningPathResponse generateLocalDemoPath(Long userId) {
        if (!(profileSources.getIfAvailable() instanceof LocalLearningProfileSource)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "No learning path has been generated yet.");
        }
        return create(userId, LOCAL_DEMO_ATTEMPT_ID).path();
    }

    private LearningPathResponse loadResponse(UserLearningPath path) {
        List<UserLearningPathItem> pathItems = items.findWithLessonByLearningPathId(path.getId());
        if (pathItems.isEmpty() || pathItems.stream().anyMatch(item -> item.getPhase() == null
                || item.getDayNumber() == null || item.getEstimatedMinutes() == null)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "This learning path predates the two-phase schedule; please regenerate it.");
        }
        Set<Long> completed = progress.findCompletedLessonIds(path.getUser().getId());
        return mapper.toResponse(path, pathItems, completed);
    }

    private void supersedeActive(Long userId) {
        paths.findByUserIdAndStatus(userId, "ACTIVE").ifPresent(active -> {
            active.setStatus("SUPERSEDED");
            paths.save(active);
        });
    }

    private String buildTitle(LearningProfileSnapshot profile) {
        if (profile.targetLevel().equals(profile.assessedLevel())) {
            return "JLPT " + profile.assessedLevel();
        }
        return "JLPT " + profile.assessedLevel() + " to " + profile.targetLevel();
    }

    private static void requirePositiveUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
    }
}
