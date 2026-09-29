package com.jlas.game.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.game.dto.MemoryDtos.FlipRequest;
import com.jlas.game.dto.MemoryDtos.MemoryStartRequest;
import com.jlas.game.dto.MemoryDtos.MemoryStateView;
import com.jlas.game.service.MemoryMatchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API trong ván Memory Match. Base path: /api/games/memory/{sessionId} */
@RestController
@RequestMapping("/api/games/memory/{sessionId}")
@RequiredArgsConstructor
public class MemoryMatchController {
    private final MemoryMatchService memoryMatchService;

    @GetMapping
    public ResponseEntity<ApiResponse<MemoryStateView>> state(@PathVariable String sessionId,
                                                              @AuthenticationPrincipal UserPrincipal user) {
        return ok(memoryMatchService.state(sessionId, user.userId()));
    }

    @PostMapping("/flip")
    public ResponseEntity<ApiResponse<MemoryStateView>> flip(@PathVariable String sessionId,
                                                             @Valid @RequestBody FlipRequest request,
                                                             @AuthenticationPrincipal UserPrincipal user) {
        return ok(memoryMatchService.flip(sessionId, user.userId(), request.cardInstanceId()));
    }

    @PostMapping("/start")
    public ResponseEntity<ApiResponse<MemoryStateView>> start(@PathVariable String sessionId,
                                                              @RequestBody(required = false) MemoryStartRequest request,
                                                              @AuthenticationPrincipal UserPrincipal user) {
        return ok(memoryMatchService.start(sessionId, user.userId(), request));
    }

    @PostMapping("/pause")
    public ResponseEntity<ApiResponse<MemoryStateView>> pause(@PathVariable String sessionId,
                                                              @AuthenticationPrincipal UserPrincipal user) {
        return ok(memoryMatchService.setPaused(sessionId, user.userId(), true));
    }

    @PostMapping("/resume")
    public ResponseEntity<ApiResponse<MemoryStateView>> resume(@PathVariable String sessionId,
                                                               @AuthenticationPrincipal UserPrincipal user) {
        return ok(memoryMatchService.setPaused(sessionId, user.userId(), false));
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.ok("OK", data));
    }
}
