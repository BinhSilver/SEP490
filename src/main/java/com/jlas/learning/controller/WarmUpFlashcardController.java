package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.flashcard.dto.FlashcardDtos.CardDto;
import com.jlas.learning.service.WarmUpFlashcardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/learning/warm-up/flashcards")
@RequiredArgsConstructor
public class WarmUpFlashcardController {
    private final WarmUpFlashcardService flashcards;

    public record ProgressRequest(String memoryStatus) { }

    @PostMapping("/{setId}/cards/{cardId}/progress")
    public ApiResponse<CardDto> progress(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long setId, @PathVariable Long cardId, @RequestBody ProgressRequest request) {
        return ApiResponse.ok("Đã lưu tiến độ ôn tập",
                flashcards.remember(user.userId(), setId, cardId, request.memoryStatus()));
    }
}
