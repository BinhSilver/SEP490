package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.service.impl.JpathAiExplainAllowance;
import com.jlas.jlptpath.service.impl.JpathAiExplainService;
import com.jlas.jlptpath.service.impl.JpathAiExplainService.AskRequest;
import com.jlas.jlptpath.service.impl.JpathAiExplainService.AskResponse;
import com.jlas.jlptpath.service.impl.JpathAiExplainService.ExplainRequest;
import com.jlas.jlptpath.service.impl.JpathAiExplainService.ExplainResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [JLAS-W0108] F36: API giải thích câu hỏi và hỏi thêm bằng AI cho cả Lộ trình JLPT lẫn Lộ trình Giao tiếp.
 */
@RestController
@RequestMapping("/api/learning/ai-explain")
@RequiredArgsConstructor
public class JpathAiExplainController {

    private final JpathAiExplainAllowance allowance;
    private final JpathAiExplainService explainService;

    @GetMapping("/allowance")
    public ResponseEntity<ApiResponse<JpathAiExplainAllowance.Status>> status(
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", allowance.status(user != null ? user.userId() : null)));
    }

    @PostMapping("/pack")
    public ResponseEntity<ApiResponse<JpathAiExplainAllowance.Status>> buyPack(
            @AuthenticationPrincipal UserPrincipal user) {
        Long userId = user != null ? user.userId() : null;
        return ResponseEntity.ok(ApiResponse.ok(
                "Đã mua " + JpathAiExplainAllowance.PACK_TURNS + " lượt AI giải thích",
                allowance.buyPack(userId)));
    }

    @PostMapping("/explain")
    public ResponseEntity<ApiResponse<ExplainResponse>> explain(
            @AuthenticationPrincipal UserPrincipal user,
            @RequestBody ExplainRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("OK",
                explainService.explain(user != null ? user.userId() : null, request)));
    }

    @PostMapping("/ask")
    public ResponseEntity<ApiResponse<AskResponse>> ask(
            @AuthenticationPrincipal UserPrincipal user,
            @RequestBody AskRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("OK",
                explainService.ask(user != null ? user.userId() : null, request)));
    }
}
