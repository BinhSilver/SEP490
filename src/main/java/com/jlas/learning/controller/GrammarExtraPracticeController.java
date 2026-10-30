package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.dto.GrammarExtraPractice;
import com.jlas.learning.service.impl.GrammarExtraPracticeService;
import com.jlas.learning.service.impl.InteractiveRateLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** [JLAS-W0108] Owner 2026-10-21: optional AI-written practice sentences for one grammar pattern (unscored). */
@RestController
@RequestMapping("/api/learning/v2/activities/{activityId}/grammar")
@RequiredArgsConstructor
public class GrammarExtraPracticeController {
    private final GrammarExtraPracticeService service;
    private final InteractiveRateLimitService rateLimit;

    @PostMapping("/points/{grammarPointId}/extra-practice")
    public ResponseEntity<ApiResponse<GrammarExtraPractice>> generate(
            @PathVariable Long activityId, @PathVariable Long grammarPointId, @AuthenticationPrincipal UserPrincipal user) {
        rateLimit.check(user.userId(), InteractiveRateLimitService.Bucket.GRAMMAR_EXTRA_PRACTICE);
        return ResponseEntity.ok(ApiResponse.ok("OK", service.generate(user.userId(), activityId, grammarPointId)));
    }
}
