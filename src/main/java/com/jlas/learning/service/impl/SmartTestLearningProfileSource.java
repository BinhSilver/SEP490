package com.jlas.learning.service.impl;

import com.jlas.assessment.entity.AssessmentAnswer;
import com.jlas.assessment.entity.AssessmentAttempt;
import com.jlas.assessment.entity.SmartTestSession;
import com.jlas.assessment.entity.UserLearningSurvey;
import com.jlas.assessment.repository.AssessmentAnswerRepository;
import com.jlas.assessment.repository.AssessmentAttemptRepository;
import com.jlas.assessment.repository.SmartTestSessionRepository;
import com.jlas.assessment.repository.UserLearningSurveyRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.TagAssessmentResult;
import com.jlas.learning.service.LearningProfileSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production adapter: builds a {@link LearningProfileSnapshot} from a real, finalized Smart Test
 * attempt. Ownership and SUBMITTED status are verified server-side; per-tag evidence is aggregated
 * from GRAMMAR answers by walking {@code question.grammarPoint -> lesson -> knowledgeTag} (the lesson
 * tag N5-Bxx), never from the goal tag stored on {@code question.knowledgeTag}.
 */
@Component
@Primary
@RequiredArgsConstructor
public class SmartTestLearningProfileSource implements LearningProfileSource {
    private static final String ASSESSMENT_REVISION = "smart-test-v1";

    private final AssessmentAttemptRepository attempts;
    private final SmartTestSessionRepository sessions;
    private final UserLearningSurveyRepository surveys;
    private final AssessmentAnswerRepository answers;

    @Override
    @Transactional(readOnly = true)
    public LearningProfileSnapshot loadForUser(Long userId, Long assessmentAttemptId) {
        AssessmentAttempt attempt = attempts.findByIdAndUserId(assessmentAttemptId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Assessment attempt not found."));
        if (!"SUBMITTED".equals(attempt.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Smart Test results are not finalized yet.");
        }
        SmartTestSession session = sessions.findById(assessmentAttemptId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Smart Test session not found."));
        UserLearningSurvey survey = surveys.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Learning survey not found."));

        return new LearningProfileSnapshot(userId, assessmentAttemptId, survey.getUserId(),
                ASSESSMENT_REVISION, survey.getCompletedAt() != null, true,
                assessedLevel(session), targetLevel(session, survey), session.getGoal(),
                session.getDailyStudyMinutes() != null ? session.getDailyStudyMinutes() : survey.getDailyStudyMinutes(),
                aggregateTagResults(assessmentAttemptId));
    }

    private static String assessedLevel(SmartTestSession session) {
        return session.getEstimatedOverallLevel() != null
                ? session.getEstimatedOverallLevel()
                : session.getSelfReportedLevel();
    }

    private static String targetLevel(SmartTestSession session, UserLearningSurvey survey) {
        return session.getTargetLevel() != null ? session.getTargetLevel() : survey.getTargetLevel();
    }

    /** GRAMMAR answers only, aggregated by the lesson tag behind each answer's primary grammar point. */
    private List<TagAssessmentResult> aggregateTagResults(Long attemptId) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (AssessmentAnswer answer : answers.findGrammarWithLessonTagByAttemptId(attemptId)) {
            if (answer.getSelectedOption() == null) continue;
            String tagCode = answer.getQuestion().getGrammarPoint().getLesson().getKnowledgeTag().getCode();
            int[] count = counts.computeIfAbsent(tagCode, key -> new int[2]);
            count[0]++;
            if (Boolean.FALSE.equals(answer.getIsCorrect())) count[1]++;
        }
        return counts.entrySet().stream()
                .map(entry -> new TagAssessmentResult(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .toList();
    }
}
