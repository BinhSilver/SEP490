package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.service.impl.JpathGrammarMistakes;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** [JLAS-W0108] Q6: read-only "Ngữ pháp hay sai" list for the "Lộ trình học" notebook. */
@RestController
@RequiredArgsConstructor
public class JpathGrammarMistakesController {

    private final JpathGrammarMistakes mistakes;

    @GetMapping("/api/learning/jlpt/grammar-mistakes")
    public ResponseEntity<ApiResponse<JpathGrammarMistakes.View>> list(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", mistakes.read(user.userId())));
    }
}
