package com.jlas.assessment.controller;

import com.jlas.assessment.service.AssessmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller xử lý các API kiểm tra / đánh giá trình độ.
 * Base path: /api/assessment
 * TODO: Thêm các endpoint cụ thể.
 */
@RestController
@RequestMapping("/api/assessment")
@RequiredArgsConstructor
public class AssessmentController {

    private final AssessmentService assessmentService;
}
