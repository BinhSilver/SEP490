package com.jlas.assessment.service;

import com.jlas.assessment.dto.SmartTestAnswerRequest;
import com.jlas.assessment.dto.SmartTestResponse;
import com.jlas.assessment.dto.SmartTestStartRequest;
import com.jlas.assessment.dto.LearningSurveyResponse;

/**
 * Service xử lý nghiệp vụ kiểm tra / đánh giá trình độ.
 */
public interface AssessmentService {
    LearningSurveyResponse getLearningSurvey(Long userId);

    LearningSurveyResponse saveLearningSurvey(Long userId, SmartTestStartRequest request);

    SmartTestResponse startSmartTest(Long userId, SmartTestStartRequest request);

    SmartTestResponse getSmartTest(Long userId, Long attemptId);

    SmartTestResponse answerSmartTest(Long userId, Long attemptId, SmartTestAnswerRequest request);

    SmartTestResponse finishSmartTest(Long userId, Long attemptId);
}
