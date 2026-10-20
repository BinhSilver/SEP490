package com.jlas.learning.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.*;
import com.jlas.learning.entity.*;
import com.jlas.learning.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GoalSurveyService {
    private static final String APPROVED = "APPROVED";

    private final LearningGoalRepository goals;
    private final LearningSurveyQuestionRepository questions;
    private final LearningSurveyOptionRepository options;
    private final UserGoalSurveySubmissionRepository submissions;
    private final UserGoalSurveySelectionRepository selections;

    @Transactional(readOnly = true)
    public GoalSurveyDefinition definition(String rawGoalCode) {
        SurveyData data = load(rawGoalCode);
        Map<Long, List<LearningSurveyOption>> byQuestion = data.options().stream()
                .collect(Collectors.groupingBy(LearningSurveyOption::getQuestionId));
        return new GoalSurveyDefinition(data.goal().getGoalCode(), data.goal().getDisplayNameVi(),
                data.goal().getEntryPolicy(), data.goal().isRequiresAssessment(),
                data.questions().stream().map(question -> new GoalSurveyDefinition.Question(
                        question.getSurveyQuestionCode(), question.getQuestionOrder(), question.getQuestionVi(),
                        question.getSelectionType(), question.isRequired(),
                        byQuestion.getOrDefault(question.getId(), List.of()).stream().map(option ->
                                new GoalSurveyDefinition.Option(option.getSurveyOptionCode(), option.getOptionOrder(),
                                        option.getLabelVi(), option.getValueCode(), null, null,
                                        option.getLearningMode())).toList())).toList());
    }

    @Transactional
    public GoalSurveySubmissionResponse submit(Long userId, String rawGoalCode,
                                                GoalSurveySubmissionRequest request) {
        if (userId == null || userId <= 0 || request == null || request.answers() == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Dữ liệu khảo sát không hợp lệ.");
        }
        SurveyData data = load(rawGoalCode);
        GoalEntryPolicy policy;
        try {
            policy = GoalEntryPolicy.forGoal(data.goal().getGoalCode());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.BAD_REQUEST, ex.getMessage());
        }
        validateMode(request.learningMode(), policy);

        Map<String, LearningSurveyQuestion> questionByCode = data.questions().stream()
                .collect(Collectors.toMap(LearningSurveyQuestion::getSurveyQuestionCode, Function.identity()));
        Map<String, LearningSurveyOption> optionByCode = data.options().stream()
                .collect(Collectors.toMap(LearningSurveyOption::getSurveyOptionCode, Function.identity()));
        Map<String, GoalSurveyAnswerRequest> answerByQuestion = request.answers().stream()
                .collect(Collectors.toMap(GoalSurveyAnswerRequest::questionCode, Function.identity(),
                        (left, right) -> { throw new ApiException(ErrorCode.BAD_REQUEST,
                                "Mỗi câu hỏi chỉ được gửi một lần."); }));

        for (LearningSurveyQuestion question : data.questions()) {
            GoalSurveyAnswerRequest answer = answerByQuestion.get(question.getSurveyQuestionCode());
            if (question.isRequired() && (answer == null || answer.optionCodes() == null || answer.optionCodes().isEmpty())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Vui lòng trả lời đầy đủ khảo sát.");
            }
            if (answer == null) continue;
            if (answer.optionCodes() == null || answer.optionCodes().isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Câu trả lời khảo sát phải có ít nhất một lựa chọn.");
            }
            if ("SINGLE".equals(question.getSelectionType()) && answer.optionCodes().size() != 1) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Câu hỏi chỉ cho phép chọn một phương án.");
            }
            for (String optionCode : new LinkedHashSet<>(answer.optionCodes())) {
                LearningSurveyOption option = optionByCode.get(optionCode);
                if (option == null || !Objects.equals(option.getQuestionId(), question.getId())) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED,
                            "Phương án không thuộc câu hỏi hoặc chưa được duyệt.");
                }
            }
        }
        if (!questionByCode.keySet().containsAll(answerByQuestion.keySet())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Câu hỏi không thuộc khảo sát hiện tại.");
        }

        int version = submissions.findMaxVersion(userId, data.goal().getId()) + 1;
        UserGoalSurveySubmission saved = submissions.save(UserGoalSurveySubmission.builder()
                .userId(userId).goalId(data.goal().getId()).learningMode(normalize(request.learningMode()))
                .status("SUBMITTED").version(version).submittedAt(LocalDateTime.now()).build());

        List<UserGoalSurveySelection> rows = new ArrayList<>();
        for (GoalSurveyAnswerRequest answer : request.answers()) {
            LearningSurveyQuestion question = questionByCode.get(answer.questionCode());
            for (String optionCode : new LinkedHashSet<>(answer.optionCodes())) {
                rows.add(UserGoalSurveySelection.builder().submissionId(saved.getId()).questionId(question.getId())
                        .optionId(optionByCode.get(optionCode).getId()).build());
            }
        }
        selections.saveAll(rows);
        return new GoalSurveySubmissionResponse(saved.getId(), data.goal().getGoalCode(), version,
                saved.getLearningMode(), policy.assessmentRequirement().name(),
                policy.competencySelfReportIsEvidence());
    }

    private SurveyData load(String rawGoalCode) {
        String goalCode = normalize(rawGoalCode);
        LearningGoal goal = goals.findByGoalCodeAndActiveTrue(goalCode)
                .filter(candidate -> APPROVED.equals(candidate.getReviewStatus()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Mục tiêu học chưa sẵn sàng."));
        List<LearningSurveyQuestion> approvedQuestions = questions
                .findByGoalIdAndActiveTrueAndReviewStatusOrderByQuestionOrder(goal.getId(), APPROVED);
        if (approvedQuestions.isEmpty()) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Khảo sát chưa có nội dung đã duyệt.");
        }
        List<Long> ids = approvedQuestions.stream().map(LearningSurveyQuestion::getId).toList();
        List<LearningSurveyOption> approvedOptions = options
                .findByQuestionIdInAndReviewStatusOrderByQuestionIdAscOptionOrderAsc(ids, APPROVED);
        Set<Long> questionIdsWithOptions = approvedOptions.stream()
                .map(LearningSurveyOption::getQuestionId).collect(Collectors.toSet());
        if (approvedQuestions.stream().anyMatch(question -> question.isRequired()
                && !questionIdsWithOptions.contains(question.getId()))) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                    "Khảo sát chưa có đủ lựa chọn đã duyệt.");
        }
        return new SurveyData(goal, approvedQuestions, approvedOptions);
    }

    private static void validateMode(String rawMode, GoalEntryPolicy policy) {
        String mode = normalize(rawMode);
        if (mode != null && !policy.allowedLearningModes().contains(mode)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chế độ học không phù hợp mục tiêu.");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private record SurveyData(LearningGoal goal, List<LearningSurveyQuestion> questions,
                              List<LearningSurveyOption> options) {}
}
