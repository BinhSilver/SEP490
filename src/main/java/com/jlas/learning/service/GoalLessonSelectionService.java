package com.jlas.learning.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.learning.entity.LearningSurveyOption;
import com.jlas.learning.entity.LessonLearningScope;
import com.jlas.learning.entity.UserGoalSurveySubmission;
import com.jlas.learning.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Selects approved canonical lessons from an immutable structured-survey submission. */
@Service
@RequiredArgsConstructor
public class GoalLessonSelectionService {
    private final UserGoalSurveySubmissionRepository submissions;
    private final UserGoalSurveySelectionRepository selections;
    private final LearningSurveyOptionRepository options;
    private final LessonLearningScopeRepository scopes;
    private final LessonRepository lessons;

    @Transactional(readOnly = true)
    public GoalLessonSelection select(Long userId, Long submissionId) {
        UserGoalSurveySubmission submission = submissions.findByIdAndUserId(submissionId, userId)
                .filter(row -> "SUBMITTED".equals(row.getStatus()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy khảo sát đã nộp."));
        List<Long> optionIds = selections.findBySubmissionId(submissionId).stream()
                .map(row -> row.getOptionId()).distinct().toList();
        List<LearningSurveyOption> chosen = optionIds.isEmpty() ? List.of() : options.findAllById(optionIds);
        Set<Long> topicIds = chosen.stream().map(LearningSurveyOption::getTopicId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> competencyIds = chosen.stream().map(LearningSurveyOption::getCompetencyId)
                .filter(Objects::nonNull).collect(Collectors.toSet());

        List<LessonLearningScope> eligible = scopes
                .findByGoalIdAndReviewStatusOrderByScopeOrderAsc(submission.getGoalId(), "APPROVED").stream()
                .filter(scope -> scope.getLearningMode() == null
                        || Objects.equals(scope.getLearningMode(), submission.getLearningMode()))
                .filter(scope -> scope.isRequired()
                        || (scope.getTopicId() != null && topicIds.contains(scope.getTopicId()))
                        || (scope.getCompetencyId() != null && competencyIds.contains(scope.getCompetencyId())))
                .toList();
        if (eligible.isEmpty()) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                    "Chưa có bài học đã duyệt phù hợp với nhu cầu đã chọn.");
        }

        List<Long> orderedIds = eligible.stream().map(LessonLearningScope::getLessonId).distinct().toList();
        Map<Long, Lesson> published = lessons.findAllById(orderedIds).stream()
                .filter(lesson -> "PUBLISHED".equals(lesson.getStatus()))
                .collect(Collectors.toMap(Lesson::getId, Function.identity()));
        if (!published.keySet().containsAll(orderedIds)) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                    "Một số bài học trong lộ trình chưa được xuất bản.");
        }
        return new GoalLessonSelection(submissionId, submission.getGoalId(), submission.getLearningMode(),
                orderedIds.stream().map(published::get).toList());
    }

    public record GoalLessonSelection(Long submissionId, Long goalId, String learningMode, List<Lesson> lessons) {
        public GoalLessonSelection { lessons = List.copyOf(lessons); }
    }
}
