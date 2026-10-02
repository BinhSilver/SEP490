package com.jlas.learning.service.impl;

import com.jlas.assessment.repository.AssessmentQuestionRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.*;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.*;
import com.jlas.learning.service.ComprehensionExerciseCatalog;
import com.jlas.learning.service.InteractiveComprehensionService;
import com.jlas.learning.service.impl.InteractiveActivityStatePolicy;
import com.jlas.learning.service.impl.engine.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Shared Reading/Listening service. Controllers are intentionally deferred to RC Task 5. */
@Service
public class InteractiveComprehensionServiceImpl implements InteractiveComprehensionService {
    private final UserLearningPathItemRepository items;
    private final LearningActivityMemberRepository members;
    private final AssessmentComprehensionContentAdapter readingAdapter;
    private final AssessmentComprehensionContentAdapter listeningAdapter;
    private final InteractiveStudyEngine<ComprehensionContent> readingEngine;
    private final InteractiveStudyEngine<ComprehensionContent> listeningEngine;

    public InteractiveComprehensionServiceImpl(UserLearningPathItemRepository items,
            LearningActivityMemberRepository members, LearningStudySessionRepository studySessions,
            LearningStudyCardRepository studyCards, LearningMicroCheckStateRepository checkStates,
            LearningQuizSessionRepository quizSessions, AssessmentQuestionRepository questions,
            ComprehensionExerciseCatalog catalog, LearningMapper mapper) {
        this.items = items;
        this.members = members;
        this.readingAdapter = new AssessmentComprehensionContentAdapter(LearningActivityType.READING, questions, catalog);
        this.listeningAdapter = new AssessmentComprehensionContentAdapter(LearningActivityType.LISTENING, questions, catalog);
        this.readingEngine = new InteractiveStudyEngine<>(items, members, studySessions, studyCards,
                checkStates, quizSessions, mapper, readingAdapter);
        this.listeningEngine = new InteractiveStudyEngine<>(items, members, studySessions, studyCards,
                checkStates, quizSessions, mapper, listeningAdapter);
    }

    @Override @Transactional
    public ComprehensionStudyState studyState(Long userId, Long activityId) {
        UserLearningPathItem activity = ownedComprehension(userId, activityId);
        var engine = engine(activity.getActivityType());
        MicroCheckStateResponse check = engine.microCheckState(userId, activityId);
        var adapter = adapter(activity.getActivityType());
        List<LearningActivityMember> membership = membership(activityId);
        Map<Long, ComprehensionContent> loaded = adapter.loadByIds(
                membership.stream().map(LearningActivityMember::getCanonicalId).toList());
        List<ComprehensionContent> ordered = membership.stream().map(member -> loaded.get(member.getCanonicalId())).toList();
        if (ordered.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Nội dung bài đọc/nghe không còn sẵn sàng.");
        }
        adapter.validateSourceRevision(membership.getFirst().getSourceRevision(), ordered);
        List<List<ComprehensionContent>> batches = adapter.partition(ordered);
        int selected = Math.min(check.currentBatch(), batches.size() - 1);
        List<ComprehensionContent> batch = batches.get(selected);
        var exercise = batch.getFirst().exercise();
        var source = activity.getActivityType() == LearningActivityType.READING
                ? new ComprehensionExerciseSource(exercise.source(), null)
                : new ComprehensionExerciseSource(null, exercise.source());
        List<ComprehensionStudyQuestion> questions = batch.stream()
                .map(content -> new ComprehensionStudyQuestion(content.question().id(),
                        content.question().prompt(), content.question().options())).toList();
        return new ComprehensionStudyState(activityId, activity.getActivityType(), check.activityState(),
                membership.getFirst().getSourceRevision(), batches.size(), selected, source, questions, check);
    }

    @Override @Transactional
    public MicroCheckResponse issueMicroCheck(Long userId, Long activityId) {
        return engineFor(userId, activityId).issueMicroCheck(userId, activityId);
    }

    @Override @Transactional
    public MicroCheckResult submitMicroCheck(Long userId, Long activityId, MicroCheckSubmitRequest request) {
        return engineFor(userId, activityId).submitMicroCheck(userId, activityId, request);
    }

    @Override @Transactional
    public MicroCheckStateResponse microCheckState(Long userId, Long activityId) {
        return engineFor(userId, activityId).microCheckState(userId, activityId);
    }

    @Override @Transactional
    public MicroCheckStateResponse recordReviewEvidence(Long userId, Long activityId) {
        return engineFor(userId, activityId).recordReviewEvidence(userId, activityId);
    }

    @Override @Transactional
    public MicroCheckStateResponse completeReview(Long userId, Long activityId) {
        return engineFor(userId, activityId).completeReview(userId, activityId);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<com.jlas.learning.dto.ComprehensionAttemptFeedback> reviewFeedback(Long userId, Long activityId) {
        return engineFor(userId, activityId).reviewEntries(userId, activityId).stream().map(entry -> {
            var f = entry.feedback();
            var source = ComprehensionSourceSnapshot.parse(entry.sourceSnapshotJson());
            return new com.jlas.learning.dto.ComprehensionAttemptFeedback(f.batchIndex(), f.scorePercent(), f.correctAnswers(),
                    f.totalQuestions(), f.passed(), f.submittedAt(),
                    source.isPresent() ? com.jlas.learning.dto.ComprehensionAttemptFeedback.AVAILABLE
                            : com.jlas.learning.dto.ComprehensionAttemptFeedback.UNAVAILABLE,
                    source.orElse(null), f.review());
        }).toList();
    }

    @Override
    @Transactional
    public com.jlas.learning.dto.ComprehensionReplayResponse issuePractice(Long userId, Long activityId) {
        var issued = engineFor(userId, activityId).issuePracticeWithSource(userId, activityId);
        var source = ComprehensionSourceSnapshot.parse(issued.sourceSnapshotJson())
                .orElseThrow(() -> new ApiException(ErrorCode.BLOCKED_CONTENT, "Không dựng được nguồn bài đọc/nghe cho lượt luyện tập."));
        var r = issued.response();
        return new com.jlas.learning.dto.ComprehensionReplayResponse(r.attemptId(), r.activityId(), r.batchIndex(),
                r.batchCount(), source, r.questions());
    }

    @Override
    @Transactional
    public MicroCheckResult submitPractice(Long userId, Long activityId, MicroCheckSubmitRequest request) {
        return engineFor(userId, activityId).submitPractice(userId, activityId, request);
    }

    private InteractiveStudyEngine<ComprehensionContent> engineFor(Long userId, Long activityId) {
        return engine(ownedComprehension(userId, activityId).getActivityType());
    }

    private UserLearningPathItem ownedComprehension(Long userId, Long activityId) {
        UserLearningPathItem activity = items.findOwnedByIdForUpdate(activityId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (activity.getActivityType() != LearningActivityType.READING
                && activity.getActivityType() != LearningActivityType.LISTENING) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        InteractiveActivityStatePolicy.requireUnlocked(activity);
        return activity;
    }

    private InteractiveStudyEngine<ComprehensionContent> engine(LearningActivityType type) {
        return type == LearningActivityType.READING ? readingEngine : listeningEngine;
    }

    private AssessmentComprehensionContentAdapter adapter(LearningActivityType type) {
        return type == LearningActivityType.READING ? readingAdapter : listeningAdapter;
    }

    private List<LearningActivityMember> membership(Long activityId) {
        List<LearningActivityMember> result = members.findByActivityIdOrderByOrderIndex(activityId).stream()
                .filter(member -> member.getMemberType() == LearningMemberType.COMPREHENSION_QUESTION).toList();
        if (result.isEmpty()) throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Hoạt động chưa có câu hỏi.");
        return result;
    }
}
