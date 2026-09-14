package com.jlas.flashcard.controller;
import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.flashcard.dto.SpeedRecallDtos.*;
import com.jlas.flashcard.service.SpeedRecallService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/flashcards/{setId}/speed-recall")
public class FlashcardSpeedRecallController {
    private final SpeedRecallService service;
    private Long uid(UserPrincipal user) { return user == null ? null : user.userId(); }
    @GetMapping("/options")
    ApiResponse<Options> options(@PathVariable Long setId, @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", service.options(setId, uid(user)));
    }
    @PostMapping("/sessions")
    ApiResponse<View> create(@PathVariable Long setId, @AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody CreateRequest body) {
        return ApiResponse.ok("OK", service.create(setId, uid(user), body));
    }
    @GetMapping("/sessions/{id}")
    ApiResponse<View> state(@PathVariable Long setId, @PathVariable Long id, @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", service.state(setId, uid(user), id));
    }
    @GetMapping("/sessions/{id}/results")
    ApiResponse<View> results(@PathVariable Long setId, @PathVariable Long id, @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", service.state(setId, uid(user), id));
    }
    @PostMapping("/sessions/{id}/next")
    ApiResponse<View> next(@PathVariable Long setId, @PathVariable Long id, @AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody NextRequest body) {
        return ApiResponse.ok("OK", service.next(setId, uid(user), id, body));
    }
    @PostMapping("/sessions/{id}/answers")
    ApiResponse<View> answer(@PathVariable Long setId, @PathVariable Long id, @AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody AnswerRequest body) {
        return ApiResponse.ok("OK", service.answer(setId, uid(user), id, body, false));
    }
    @PostMapping("/sessions/{id}/timeout")
    ApiResponse<View> timeout(@PathVariable Long setId, @PathVariable Long id, @AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody AnswerRequest body) {
        return ApiResponse.ok("OK", service.answer(setId, uid(user), id, body, true));
    }
    @PostMapping("/sessions/{id}/pause")
    ApiResponse<View> pause(@PathVariable Long setId, @PathVariable Long id, @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", service.pause(setId, uid(user), id));
    }
    @PostMapping("/sessions/{id}/resume")
    ApiResponse<View> resume(@PathVariable Long setId, @PathVariable Long id, @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", service.resume(setId, uid(user), id));
    }
    @PostMapping("/sessions/{id}/abandon")
    ApiResponse<View> abandon(@PathVariable Long setId, @PathVariable Long id, @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", service.abandon(setId, uid(user), id));
    }
    @PostMapping("/sessions/{id}/retry-wrong")
    ApiResponse<View> retry(@PathVariable Long setId, @PathVariable Long id, @AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody RetryRequest body) {
        return ApiResponse.ok("OK", service.retry(setId, uid(user), id, body));
    }
    @GetMapping("/history")
    ApiResponse<History> history(@PathVariable Long setId, @AuthenticationPrincipal UserPrincipal user,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok("OK", service.history(setId, uid(user), page, size));
    }
}
