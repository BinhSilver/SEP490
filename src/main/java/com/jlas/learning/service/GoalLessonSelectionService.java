package com.jlas.learning.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.learning.entity.LessonLearningScope;
import com.jlas.learning.entity.UserGoalSurveySubmission;
import com.jlas.learning.entity.CompetencyLevel;
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
    private final LessonLearningScopeRepository scopes;
    private final LessonRepository lessons;
    private final CompetencyLevelRepository competencyLevels;

    @Transactional(readOnly = true)
    public GoalLessonSelection select(Long userId, Long submissionId) {
        UserGoalSurveySubmission submission = submissions.findByIdAndUserId(submissionId, userId)
                .filter(row -> "SUBMITTED".equals(row.getStatus()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy khảo sát đã nộp."));
        // Survey answers describe needs; they do not remove situations from a communication path.
        List<LessonLearningScope> eligible = scopes
                .findByGoalIdAndReviewStatusOrderByScopeOrderAsc(submission.getGoalId(), "APPROVED");
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
        Map<Long, String> levelCodes = competencyLevels.findAllById(eligible.stream()
                        .map(LessonLearningScope::getCompetencyLevelId).filter(Objects::nonNull).distinct().toList())
                .stream().filter(CompetencyLevel::isActive)
                .collect(Collectors.toMap(CompetencyLevel::getId, CompetencyLevel::getLevelCode));
        Map<Long, String> levelByLesson = new LinkedHashMap<>();
        eligible.forEach(scope -> levelByLesson.putIfAbsent(scope.getLessonId(),
                levelCodes.getOrDefault(scope.getCompetencyLevelId(), "BASIC")));
        return new GoalLessonSelection(submissionId, submission.getGoalId(), submission.getLearningMode(),
                orderedIds.stream().map(published::get).toList(), levelByLesson);
    }

    public record GoalLessonSelection(Long submissionId, Long goalId, String learningMode, List<Lesson> lessons,
                                      Map<Long, String> levelByLesson) {
        public GoalLessonSelection {
            lessons = List.copyOf(lessons);
            levelByLesson = Map.copyOf(levelByLesson);
        }
    }
}
