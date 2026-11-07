package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.dto.QuizSubmitResponse;
import com.jlas.learning.service.LessonQuizService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo tools for the Onyomi quizzes (the "Demo Onyomi" panel): finish the caller's own open practice or
 * final-test attempt right or wrong, and move the clock of a final test. Only registered when
 * {@code mission.dev-tools=true}.
 */
@RestController
@RequestMapping("/api/dev/onyomi")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mission", name = "dev-tools", havingValue = "true")
public class OnyomiDevController {

    private final LessonQuizService quizService;

    public record FinishRequest(boolean correct) {
    }

    public record SecondsLeftRequest(@Min(1) @Max(LessonQuizService.UNIT_FINAL_TIME_LIMIT_SECONDS) int seconds) {
    }

    @PostMapping("/attempts/{attemptId}/finish")
    public ApiResponse<QuizSubmitResponse> finish(@PathVariable("attemptId") Long attemptId,
                                                  @RequestBody FinishRequest request,
                                                  @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok(request.correct() ? "Đã làm đúng hết và nộp bài" : "Đã làm sai hết và nộp bài",
                quizService.finishForDemo(user.userId(), attemptId, request.correct()));
    }

    @PostMapping("/attempts/{attemptId}/seconds-left")
    public ApiResponse<Map<String, Long>> secondsLeft(@PathVariable("attemptId") Long attemptId,
                                                      @Valid @RequestBody SecondsLeftRequest request,
                                                      @AuthenticationPrincipal UserPrincipal user) {
        long left = quizService.secondsLeftForDemo(user.userId(), attemptId, request.seconds());
        return ApiResponse.ok("Đã chỉnh đồng hồ", Map.of("secondsLeft", left));
    }
}
