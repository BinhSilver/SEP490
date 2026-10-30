package com.jlas.assessment.controller;

import com.jlas.assessment.dto.SmartTestAnswerRequest;
import com.jlas.assessment.dto.SmartTestResponse;
import com.jlas.assessment.dto.SmartTestStartRequest;
import com.jlas.assessment.service.AssessmentService;
import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controller xử lý các API kiểm tra / đánh giá trình độ.
 * Base path: /api/assessment
 */
@RestController
@RequestMapping("/api/assessment")
@RequiredArgsConstructor
public class AssessmentController {

    private final AssessmentService assessmentService;

    @GetMapping("/survey")
    public ApiResponse<com.jlas.assessment.dto.LearningSurveyResponse> survey(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok("Đã tải khảo sát.", assessmentService.getLearningSurvey(userId(principal)));
    }

    @PostMapping("/survey")
    public ApiResponse<com.jlas.assessment.dto.LearningSurveyResponse> saveSurvey(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody SmartTestStartRequest request) {
        return ApiResponse.ok("Đã lưu khảo sát.", assessmentService.saveLearningSurvey(userId(principal), request));
    }

    @GetMapping("/survey/target-options")
    public ApiResponse<java.util.List<com.jlas.assessment.dto.TargetOptionResponse>> targetOptions(
            @RequestParam(defaultValue = "UNKNOWN") String assessedLevel) {
        return ApiResponse.ok("Đã tải lựa chọn mục tiêu.", assessmentService.getTargetOptions(assessedLevel));
    }

    @PostMapping("/smart-tests")
    public ApiResponse<SmartTestResponse> start(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody SmartTestStartRequest request) {
        return ApiResponse.ok("Đã tạo bài Smart Test.",
                assessmentService.startSmartTest(userId(principal), request));
    }

    @GetMapping("/smart-tests/{attemptId}")
    public ApiResponse<SmartTestResponse> get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long attemptId) {
        return ApiResponse.ok("Đã tải bài Smart Test.",
                assessmentService.getSmartTest(userId(principal), attemptId));
    }

    @PostMapping("/smart-tests/{attemptId}/answers")
    public ApiResponse<SmartTestResponse> answer(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long attemptId,
            @Valid @RequestBody SmartTestAnswerRequest request) {
        return ApiResponse.ok("Đã lưu câu trả lời.",
                assessmentService.answerSmartTest(userId(principal), attemptId, request));
    }

    @PostMapping("/smart-tests/{attemptId}/finish")
    public ApiResponse<SmartTestResponse> finish(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long attemptId) {
        return ApiResponse.ok("Đã kết thúc bài Smart Test.",
                assessmentService.finishSmartTest(userId(principal), attemptId));
    }

    private static Long userId(UserPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để tiếp tục.");
        }
        return principal.userId();
    }
}
