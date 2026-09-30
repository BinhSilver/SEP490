package com.jlas.game.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.game.dto.AirDefenseDtos.AirAttackRequest;
import com.jlas.game.dto.AirDefenseDtos.AirAttackResult;
import com.jlas.game.dto.AirDefenseDtos.AirKillRequest;
import com.jlas.game.dto.AirDefenseDtos.AirKillResult;
import com.jlas.game.dto.AirDefenseDtos.AirReportRequest;
import com.jlas.game.dto.AirDefenseDtos.AirStartRequest;
import com.jlas.game.dto.AirDefenseDtos.AirStateView;
import com.jlas.game.service.AirDefenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API trong trận Air Defence. Base path: /api/games/air-defense/{sessionId} */
@RestController
@RequestMapping("/api/games/air-defense/{sessionId}")
@RequiredArgsConstructor
public class AirDefenseController {
    private final AirDefenseService airDefenseService;

    @GetMapping
    public ResponseEntity<ApiResponse<AirStateView>> state(@PathVariable String sessionId,
                                                           @AuthenticationPrincipal UserPrincipal user) {
        return ok(airDefenseService.state(sessionId, user.userId()));
    }

    /** Luyện tập: người chơi bấm "Bắt đầu" ở màn cài đặt. */
    @PostMapping("/start")
    public ResponseEntity<ApiResponse<AirStateView>> start(@PathVariable String sessionId,
                                                           @RequestBody(required = false) AirStartRequest request,
                                                           @AuthenticationPrincipal UserPrincipal user) {
        return ok(airDefenseService.start(sessionId, user.userId(), request));
    }

    @PostMapping("/kill")
    public ResponseEntity<ApiResponse<AirKillResult>> kill(@PathVariable String sessionId,
                                                           @Valid @RequestBody AirKillRequest request,
                                                           @AuthenticationPrincipal UserPrincipal user) {
        return ok(airDefenseService.kill(sessionId, user.userId(), request.wordId(), request.rawInput()));
    }

    @PostMapping("/report")
    public ResponseEntity<ApiResponse<AirStateView>> report(@PathVariable String sessionId,
                                                            @RequestBody AirReportRequest request,
                                                            @AuthenticationPrincipal UserPrincipal user) {
        return ok(airDefenseService.report(sessionId, user.userId(), request));
    }

    @PostMapping("/attack")
    public ResponseEntity<ApiResponse<AirAttackResult>> attack(@PathVariable String sessionId,
                                                               @RequestBody AirAttackRequest request,
                                                               @AuthenticationPrincipal UserPrincipal user) {
        return ok(airDefenseService.attack(sessionId, user.userId(), request.milestone()));
    }

    /** defeated=true khi hành tinh về 0 HP; false khi người chơi đơn dừng lại. */
    @PostMapping("/finish")
    public ResponseEntity<ApiResponse<AirStateView>> finish(@PathVariable String sessionId,
                                                            @RequestParam(defaultValue = "false") boolean defeated,
                                                            @RequestBody(required = false) AirReportRequest request,
                                                            @AuthenticationPrincipal UserPrincipal user) {
        return ok(airDefenseService.finish(sessionId, user.userId(), request, defeated));
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.ok("OK", data));
    }
}
