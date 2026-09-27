package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.dto.CreateLearningPathRequest;
import com.jlas.learning.dto.CreateLearningPathResult;
import com.jlas.learning.dto.LearningPathResponse;
import com.jlas.learning.dto.LessonQuizResponse;
import com.jlas.learning.dto.LessonQuizResult;
import com.jlas.learning.dto.LessonQuizSubmitRequest;
import com.jlas.learning.dto.ReplanPreviewResponse;
import com.jlas.learning.dto.ReplanRequest;
import com.jlas.learning.service.LearningService;
import com.jlas.learning.service.impl.LearningReplanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Controller API lộ trình học. Base path: /api/learning */
@RestController
@RequestMapping("/api/learning")
@RequiredArgsConstructor
public class LearningController {
    private final LearningService learningService;
    private final LearningReplanService replanService;

    @PostMapping("/paths")
    public ResponseEntity<ApiResponse<LearningPathResponse>> create(
            @RequestBody CreateLearningPathRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        CreateLearningPathResult result = learningService.create(user.userId(), request.assessmentAttemptId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(result.created() ? "Learning path created" : "Learning path already exists",
                        result.path()));
    }

    /** [JLAS-W0108] Tạo lộ trình from-zero từ survey đã hoàn thành (không cần Smart Test). */
    @PostMapping("/paths/from-zero")
    public ResponseEntity<ApiResponse<LearningPathResponse>> createFromZero(
            @AuthenticationPrincipal UserPrincipal user) {
        CreateLearningPathResult result = learningService.createFromSurvey(user.userId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(result.created() ? "Learning path created" : "Learning path already exists",
                        result.path()));
    }

    @GetMapping("/paths/current")
    public ResponseEntity<ApiResponse<LearningPathResponse>> current(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", learningService.current(user.userId())));
    }

    @PostMapping("/lessons/{lessonId}/complete")
    public ResponseEntity<ApiResponse<Void>> completeLesson(@PathVariable Long lessonId,
            @AuthenticationPrincipal UserPrincipal user) {
        learningService.completeLesson(user.userId(), lessonId);
        return ResponseEntity.ok(ApiResponse.ok("Bài học đã được ghi nhận hoàn thành"));
    }

    /** [JLAS-W0113] Lấy câu hỏi quiz của một bài học (không lộ đáp án). */
    @GetMapping("/lessons/{lessonId}/quiz")
    public ResponseEntity<ApiResponse<LessonQuizResponse>> getLessonQuiz(@PathVariable Long lessonId,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", learningService.getLessonQuiz(user.userId(), lessonId)));
    }

    /** [JLAS-W0113] Chấm bài nộp quiz; nếu đạt, tự đánh dấu bài học hoàn thành. */
    @PostMapping("/lessons/{lessonId}/quiz/submit")
    public ResponseEntity<ApiResponse<LessonQuizResult>> submitLessonQuiz(@PathVariable Long lessonId,
            @Valid @RequestBody LessonQuizSubmitRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK",
                learningService.submitLessonQuiz(user.userId(), lessonId, request)));
    }

    /** [JLAS-W0113] Lấy gate quiz tổng kết warm-up (câu hỏi gom từ các tag yếu). */
    @GetMapping("/paths/gate-quiz")
    public ResponseEntity<ApiResponse<LessonQuizResponse>> getGateQuiz(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", learningService.getGateQuiz(user.userId())));
    }

    /** [JLAS-W0113] Chấm gate quiz; nếu đạt, mở khóa giai đoạn theo mục tiêu. */
    @PostMapping("/paths/gate-quiz/submit")
    public ResponseEntity<ApiResponse<LessonQuizResult>> submitGateQuiz(
            @Valid @RequestBody LessonQuizSubmitRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", learningService.submitGateQuiz(user.userId(), request)));
    }

    /** [JLAS-W0122] Xem trước replan (không ghi) — mode REMEDIATE (mặc định) hoặc ACCELERATE. */
    @GetMapping("/paths/replan/preview")
    public ResponseEntity<ApiResponse<ReplanPreviewResponse>> previewReplan(
            @RequestParam(defaultValue = LearningReplanService.MODE_REMEDIATE) String mode,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", replanService.preview(user.userId(), mode)));
    }

    /** [JLAS-W0122] Áp dụng replan sau khi learner xác nhận; tạo version path mới. */
    @PostMapping("/paths/replan")
    public ResponseEntity<ApiResponse<LearningPathResponse>> replan(
            @RequestBody ReplanRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("Lộ trình đã được cập nhật",
                replanService.apply(user.userId(), request)));
    }
}
