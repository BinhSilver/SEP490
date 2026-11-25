package com.jlas.assessment.controller;

import com.jlas.assessment.dto.JlptStudyResponse;
import com.jlas.assessment.service.impl.JlptStudyService;
import com.jlas.auth.dto.ApiResponse;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jlpt/exams")
@RequiredArgsConstructor
@Validated
public class JlptStudyController {
    private final JlptStudyService study;
    @GetMapping("/{code}/study")
    public ResponseEntity<ApiResponse<JlptStudyResponse>> list(
            @PathVariable @Pattern(regexp = "N[1-5]_(?:\\d{4}(?:_(?:07|12))?|MOCK_\\d+)") String code) {
        return ResponseEntity.ok(ApiResponse.ok("Đã tải nội dung ôn tập trong đề.", study.list(code)));
    }
}
