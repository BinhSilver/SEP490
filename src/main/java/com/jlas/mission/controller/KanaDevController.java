package com.jlas.mission.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.mission.service.KanaDemoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo tools for Kana Connect on the challenge page. Registered only with {@code mission.dev-tools=true},
 * like the goals/streak demo; the day itself moves through /api/dev/mission/clock.
 */
@RestController
@RequestMapping("/api/dev/kana")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mission", name = "dev-tools", havingValue = "true")
public class KanaDevController {
    private final KanaDemoService demo;

    public record ChallengeRequest(@NotNull Long challengeId) {}
    public record SolveRequest(@NotNull Long challengeId, boolean all) {}
    public record PlayTimeRequest(@NotNull Long challengeId, @Min(1) @Max(3600) int seconds) {}

    @PostMapping("/hint-ready")
    public ApiResponse<Map<String, Object>> hintReady(@AuthenticationPrincipal UserPrincipal user,
                                                      @Valid @RequestBody ChallengeRequest request) {
        demo.hintReady(user.userId(), request.challengeId());
        return ApiResponse.ok("Gợi ý đã mở", Map.of());
    }

    @PostMapping("/solve")
    public ApiResponse<Map<String, Object>> solve(@AuthenticationPrincipal UserPrincipal user,
                                                  @Valid @RequestBody SolveRequest request) {
        return ApiResponse.ok("Đã giải hộ", Map.of("solved", demo.solve(user.userId(), request.challengeId(), request.all())));
    }

    @PostMapping("/play-time")
    public ApiResponse<Map<String, Object>> playTime(@AuthenticationPrincipal UserPrincipal user,
                                                     @Valid @RequestBody PlayTimeRequest request) {
        demo.addPlayTime(user.userId(), request.challengeId(), request.seconds());
        return ApiResponse.ok("Đã cộng thời gian chơi", Map.of());
    }

    @PostMapping("/restart")
    public ApiResponse<Map<String, Object>> restart(@AuthenticationPrincipal UserPrincipal user,
                                                    @Valid @RequestBody ChallengeRequest request) {
        demo.restart(user.userId(), request.challengeId());
        return ApiResponse.ok("Đã xoá tiến độ bàn này", Map.of());
    }

    @PostMapping("/players")
    public ApiResponse<Map<String, Object>> addPlayers(@AuthenticationPrincipal UserPrincipal user,
                                                       @RequestParam String level) {
        return ApiResponse.ok("Đã thêm người chơi demo", Map.of("added", demo.addDemoPlayers(user.userId(), level)));
    }

    @DeleteMapping("/players")
    public ApiResponse<Map<String, Object>> clearPlayers() {
        return ApiResponse.ok("Đã xoá người chơi demo", Map.of("removed", demo.clearDemoPlayers()));
    }
}
