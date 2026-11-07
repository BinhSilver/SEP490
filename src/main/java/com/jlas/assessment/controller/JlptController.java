package com.jlas.assessment.controller;

import com.jlas.assessment.dto.JlptExamResponse;
import com.jlas.assessment.dto.JlptExamSummaryResponse;
import com.jlas.assessment.dto.JlptExplanationRequest;
import com.jlas.assessment.dto.JlptFuriganaRequest;
import com.jlas.assessment.dto.JlptFuriganaResponse;
import com.jlas.assessment.dto.JlptExplanationResponse;
import com.jlas.assessment.dto.JlptPracticeQuestionResponse;
import com.jlas.assessment.dto.JlptPracticeTypeResponse;
import com.jlas.assessment.dto.JlptWrongAnswerRequest;
import com.jlas.assessment.service.impl.JlptWeaknessNotebookService;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.assessment.service.JlptExplanationService;
import com.jlas.assessment.service.JlptFuriganaService;
import com.jlas.assessment.service.JlptService;
import com.jlas.auth.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@RestController
@RequestMapping("/api/jlpt")
@RequiredArgsConstructor
@Validated
public class JlptController {

    private final JlptService service;
    private final JlptExplanationService explanationService;
    private final JlptFuriganaService furiganaService;
    private final JlptWeaknessNotebookService weaknessNotebook;

    @GetMapping("/exams")
    public ApiResponse<List<JlptExamSummaryResponse>> exams(
            @RequestParam(defaultValue = "N3") @Pattern(regexp = "N[1-5]") String level) {
        return ApiResponse.ok("Đã tải danh sách đề JLPT.", service.listExams(level));
    }

    @GetMapping("/exams/{code}")
    public ApiResponse<JlptExamResponse> exam(
            @PathVariable @Pattern(regexp = "N[1-5]_(?:\\d{4}_(?:07|12)|MOCK_\\d+)") String code) {
        return ApiResponse.ok("Đã tải đề JLPT.", service.getExam(code));
    }

    @GetMapping("/practice/types")
    public ApiResponse<List<JlptPracticeTypeResponse>> practiceTypes(
            @RequestParam(defaultValue = "N3") @Pattern(regexp = "N[1-5]") String level) {
        return ApiResponse.ok("Đã tải danh sách dạng câu hỏi.", service.listPracticeTypes(level));
    }

    @GetMapping("/practice/questions")
    public ApiResponse<List<JlptPracticeQuestionResponse>> practiceQuestions(
            @RequestParam(defaultValue = "N3") @Pattern(regexp = "N[1-5]") String level,
            @RequestParam @Pattern(regexp = "vocabulary|grammar|reading|listening") String section,
            @RequestParam @Pattern(regexp = "[A-Z0-9_]{2,50}") String type,
            @RequestParam(defaultValue = "20") @Min(5) @Max(50) int limit) {
        return ApiResponse.ok("Đã tạo bộ câu luyện tập.", service.practiceQuestions(level, section, type, limit));
    }

    @PostMapping("/practice/wrong-vocabulary")
    public ApiResponse<Boolean> saveWrongVocabulary(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody JlptWrongAnswerRequest request) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để lưu từ vào sổ tay.");
        boolean saved = weaknessNotebook.recordWrongAnswer(principal.userId(), request.questionId(), request.selectedOption());
        return ApiResponse.ok(saved ? "Đã thêm từ Goi vào sổ tay điểm yếu." : "Câu này chưa có từ mục tiêu để lưu.", saved);
    }

    @PostMapping("/explanations")
    public ApiResponse<JlptExplanationResponse> explanation(
            @Valid @RequestBody JlptExplanationRequest request) {
        return ApiResponse.ok("Đã tạo giải thích.", explanationService.explain(request));
    }

    @PostMapping("/furigana")
    public ApiResponse<JlptFuriganaResponse> furigana(
            @Valid @RequestBody JlptFuriganaRequest request) {
        return ApiResponse.ok("Đã tạo Furigana.",
                new JlptFuriganaResponse(furiganaService.annotate(request.texts())));
    }

}
