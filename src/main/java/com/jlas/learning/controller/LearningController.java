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
        CreateLearningPathResult result = learningService.create(user.userId(), request.assessmentAttemptId(),
                request.reviewStartLevel(), request.discrepancyChoice(), request.kanaKnown(), request.entryChoice());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(result.created() ? "Learning path created" : "Learning path already exists",
                        result.path()));
    }

    /** Owner 2026-10-25: the confirmation questions a Smart Test result asks before its path is created. */
    @GetMapping("/paths/entry-questions")
    public ResponseEntity<ApiResponse<com.jlas.learning.dto.LearningEntryQuestions>> entryQuestions(
            @RequestParam Long assessmentAttemptId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", learningService.entryQuestions(user.userId(), assessmentAttemptId)));
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

    /** [JLAS-W0108] P2 — đánh dấu hoàn thành nội dung một activity (item), độc lập source lesson. */
    @PostMapping("/path-items/{itemId}/complete")
    public ResponseEntity<ApiResponse<Void>> completeItem(@PathVariable Long itemId,
            @AuthenticationPrincipal UserPrincipal user) {
        learningService.completeItem(user.userId(), itemId);
        return ResponseEntity.ok(ApiResponse.ok("Đã ghi nhận hoàn thành hoạt động"));
    }

    /** [JLAS-W0108][MP06] Ghi nhận người học đã mở một activity, để "Tiếp tục học" trỏ đúng chỗ. */
    @PostMapping("/path-items/{itemId}/start")
    public ResponseEntity<ApiResponse<Void>> startItem(@PathVariable Long itemId,
            @AuthenticationPrincipal UserPrincipal user) {
        learningService.startItem(user.userId(), itemId);
        return ResponseEntity.ok(ApiResponse.ok("Đã ghi nhận bắt đầu học"));
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

    /** [JLAS-W0108] Lấy bài final cuối chặng (5 kỹ năng, gộp 1 phiên). */
    @GetMapping("/stages/{stageId}/final-quiz")
    public ResponseEntity<ApiResponse<LessonQuizResponse>> getStageFinalQuiz(@PathVariable Long stageId,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", learningService.getStageFinalQuiz(user.userId(), stageId)));
    }

    /** [JLAS-W0108] Chấm bài final cuối chặng; đạt thì hoàn thành chặng/lộ trình. */
    @PostMapping("/stages/{stageId}/final-quiz/submit")
    public ResponseEntity<ApiResponse<LessonQuizResult>> submitStageFinalQuiz(@PathVariable Long stageId,
            @Valid @RequestBody LessonQuizSubmitRequest request, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK",
                learningService.submitStageFinalQuiz(user.userId(), stageId, request)));
    }

    /** [JLAS-W0108] P4 — lấy warm-up grammar quiz (retest mẫu ngữ pháp đã sai, không lộ đáp án). */
    @GetMapping("/grammar/{grammarPointId}/quiz")
    public ResponseEntity<ApiResponse<LessonQuizResponse>> getGrammarQuiz(@PathVariable Long grammarPointId,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK",
                learningService.getGrammarQuiz(user.userId(), grammarPointId)));
    }

    /** [JLAS-W0108] P4 — chấm warm-up grammar quiz; đạt ghi assessment PASSED trên item mẫu ngữ pháp. */
    @PostMapping("/grammar/{grammarPointId}/quiz/submit")
    public ResponseEntity<ApiResponse<LessonQuizResult>> submitGrammarQuiz(@PathVariable Long grammarPointId,
            @Valid @RequestBody LessonQuizSubmitRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK",
                learningService.submitGrammarQuiz(user.userId(), grammarPointId, request)));
    }

    /** [JLAS-W0108] P4/D3 — lấy flashcard quiz (MCQ chọn nghĩa toàn bộ từ/Kanji sai, không lộ đáp án). */
    @GetMapping("/paths/flashcard-quiz")
    public ResponseEntity<ApiResponse<LessonQuizResponse>> getFlashcardQuiz(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", learningService.getFlashcardQuiz(user.userId())));
    }

    /** [JLAS-W0108] P4/D3 — chấm flashcard quiz; đúng hết ghi assessment PASSED trên item từ/Kanji. */
    @PostMapping("/paths/flashcard-quiz/submit")
    public ResponseEntity<ApiResponse<LessonQuizResult>> submitFlashcardQuiz(
            @Valid @RequestBody LessonQuizSubmitRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", learningService.submitFlashcardQuiz(user.userId(), request)));
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
