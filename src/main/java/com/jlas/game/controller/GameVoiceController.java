package com.jlas.game.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.game.service.GameVoiceService;
import com.jlas.game.service.GameVoiceService.VoiceCredentials;
import com.jlas.game.service.GameVoiceService.VoiceStatus;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games/voice")
@RequiredArgsConstructor
public class GameVoiceController {
    private final GameVoiceService voice;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<VoiceStatus>> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok("OK", voice.status()));
    }

    @PostMapping("/rooms/{roomId}/token")
    public ResponseEntity<ApiResponse<VoiceCredentials>> token(@PathVariable String roomId,
            @RequestParam(required = false) String sessionId,
            @AuthenticationPrincipal UserPrincipal user, HttpServletRequest request) {
        VoiceCredentials credentials = voice.join(user.userId(), roomId, sessionId, request.getHeader("Origin"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok("OK", credentials));
    }
}
