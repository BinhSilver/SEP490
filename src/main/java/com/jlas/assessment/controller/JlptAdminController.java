package com.jlas.assessment.controller;

import com.jlas.assessment.dto.JlptAdminExamDetailResponse;
import com.jlas.assessment.dto.JlptAdminExamRequest;
import com.jlas.assessment.dto.JlptAdminExamSummaryResponse;
import com.jlas.assessment.dto.JlptAdminQuestionAudioRequest;
import com.jlas.assessment.dto.JlptAdminQuestionRequest;
import com.jlas.assessment.service.JlptAdminService;
import com.jlas.assessment.service.impl.JlptPracticeAdminService;
import com.jlas.assessment.service.impl.JlptExcelImportService;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.auth.dto.ApiResponse;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/jlpt")
@PreAuthorize("hasRole('CONTENT_CREATOR')")
@RequiredArgsConstructor
@Validated
public class JlptAdminController {

    private final JlptAdminService service;
    private final JlptPracticeAdminService practiceAdmin;
    private final JlptExcelImportService excelImport;

    @GetMapping("/import/template")
    public ResponseEntity<byte[]> importTemplate() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=jlpt-import-template.xlsx")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excelImport.template());
    }

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<JlptExcelImportService.Preview> previewImport(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok("Đã kiểm tra file Excel.", excelImport.preview(file));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<JlptAdminExamSummaryResponse> importExam(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok("Đã nhập đề vào bản nháp.", excelImport.importDraft(file));
    }

    @GetMapping("/practice-types")
    public ApiResponse<List<JlptPracticeAdminService.PracticeType>> practiceTypes() {
        return ApiResponse.ok("Đã tải các dạng luyện tập.", practiceAdmin.list());
    }

    @PutMapping("/practice-types")
    public ApiResponse<Void> updatePracticeTypes(
            @RequestBody List<JlptPracticeAdminService.PracticeTypeUpdate> updates,
            @AuthenticationPrincipal UserPrincipal principal) {
        try {
            practiceAdmin.save(updates, principal == null ? null : principal.userId());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ErrorCode.BAD_REQUEST, exception.getMessage());
        }
        return ApiResponse.ok("Đã lưu cấu hình luyện tập theo dạng.", null);
    }

    @GetMapping("/practice-types/{level}/{code}/questions")
    public ApiResponse<JlptPracticeAdminService.PracticeQuestionPage> practiceTypeQuestions(
            @PathVariable @Pattern(regexp = "N[1-5]") String level,
            @PathVariable @Pattern(regexp = "[A-Z0-9_]{2,50}") String code,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(25) int size) {
        try {
            return ApiResponse.ok("Đã tải câu hỏi của dạng luyện.", practiceAdmin.questions(level, code, page, size));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ErrorCode.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/exams")
    public ApiResponse<List<JlptAdminExamSummaryResponse>> exams() {
        return ApiResponse.ok("Đã tải kho đề JLPT.", service.listExams());
    }

    @GetMapping("/exams/{code}")
    public ApiResponse<JlptAdminExamDetailResponse> exam(@PathVariable @Pattern(regexp = "N[1-5]_(?:\\d{4}_(?:07|12)|MOCK_\\d+)") String code) {
        return ApiResponse.ok("Đã tải nội dung đề.", service.getExam(code));
    }

    @PostMapping("/exams")
    public ApiResponse<JlptAdminExamSummaryResponse> createExam(@Valid @RequestBody JlptAdminExamRequest request) {
        return ApiResponse.ok("Đã tạo đề JLPT.", service.createExam(request));
    }

    @PutMapping("/exams/{code}")
    public ApiResponse<JlptAdminExamSummaryResponse> updateExam(
            @PathVariable @Pattern(regexp = "N[1-5]_(?:\\d{4}_(?:07|12)|MOCK_\\d+)") String code,
            @Valid @RequestBody JlptAdminExamRequest request) {
        return ApiResponse.ok("Đã cập nhật đề JLPT.", service.updateExam(code, request));
    }

    @PostMapping("/exams/{code}/questions")
    public ApiResponse<Long> createQuestion(
            @PathVariable @Pattern(regexp = "N[1-5]_(?:\\d{4}_(?:07|12)|MOCK_\\d+)") String code,
            @Valid @RequestBody JlptAdminQuestionRequest request) {
        return ApiResponse.ok("Đã tạo câu hỏi.", service.createQuestion(code, request));
    }

    @PutMapping("/questions/{questionId}")
    public ApiResponse<Void> updateQuestion(@PathVariable long questionId,
                                             @Valid @RequestBody JlptAdminQuestionRequest request) {
        service.updateQuestion(questionId, request);
        return ApiResponse.ok("Đã cập nhật câu hỏi.", null);
    }

    @PutMapping("/questions/{questionId}/audio")
    public ApiResponse<Void> updateQuestionAudio(@PathVariable long questionId,
            @Valid @RequestBody JlptAdminQuestionAudioRequest request) {
        service.updateQuestionAudio(questionId, request.audioUrl());
        return ApiResponse.ok("Đã lưu riêng audio.", null);
    }

    @DeleteMapping("/questions/{questionId}")
    public ApiResponse<Void> deleteQuestion(@PathVariable long questionId) {
        service.deleteQuestion(questionId);
        return ApiResponse.ok("Đã xóa câu hỏi.", null);
    }

    @PostMapping("/questions/{questionId}/duplicate")
    public ApiResponse<Long> duplicateQuestion(@PathVariable long questionId) {
        return ApiResponse.ok("Đã nhân bản câu hỏi.", service.duplicateQuestion(questionId));
    }
}
