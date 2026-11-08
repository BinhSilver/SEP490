package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.dto.JpathGrammarLookupView;
import com.jlas.jlptpath.service.JpathLookupService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** [JLAS-W0108] Look up of grammar patterns ({@code GET /api/learning/jlpt/lookup/grammar?q=}). */
@RestController
@RequestMapping("/api/learning/jlpt/lookup")
@RequiredArgsConstructor
public class JpathLookupController {

    private final JpathLookupService lookup;

    @GetMapping("/grammar")
    public ResponseEntity<ApiResponse<List<JpathGrammarLookupView>>> grammar(
            @AuthenticationPrincipal UserPrincipal user, @RequestParam(required = false) String q) {
        return ResponseEntity.ok(ApiResponse.ok("OK", lookup.grammar(user.userId(), q)));
    }
}
