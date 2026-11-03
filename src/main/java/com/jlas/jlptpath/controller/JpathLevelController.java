package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.dto.JpathLevelSummary;
import com.jlas.jlptpath.service.JpathLevelUpService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** [JLAS-W0108] Plan 3 Task 18 — level summary of the JLPT path ({@code GET /api/learning/jlpt/levels/{level}/summary}). */
@RestController
@RequestMapping("/api/learning/jlpt/levels")
@RequiredArgsConstructor
public class JpathLevelController {

    private final JpathLevelUpService levelUp;

    @GetMapping("/{level}/summary")
    public ResponseEntity<ApiResponse<JpathLevelSummary>> summary(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String level) {
        return ResponseEntity.ok(ApiResponse.ok("OK", levelUp.summary(user.userId(), level)));
    }
}
