package com.jlas.learning.pace;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** [JLAS-W0108] One pace decision UI contract shared by JLPT and communication paths. */
@RestController
@RequestMapping("/api/learning/pace")
@RequiredArgsConstructor
public class LearningPaceController {
    private final LearningPaceService service;

    @GetMapping("/{key}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String key) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.suggestion(user.userId(), key)));
    }

    @PostMapping("/{key}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> decide(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String key, @RequestBody Map<String, Object> body) {
        boolean apply = body != null && Boolean.TRUE.equals(body.get("apply"));
        return ResponseEntity.ok(ApiResponse.ok(apply ? "Đã áp dụng nhịp mới." : "Đã giữ nhịp hiện tại.",
                service.decide(user.userId(), key, apply)));
    }
}
