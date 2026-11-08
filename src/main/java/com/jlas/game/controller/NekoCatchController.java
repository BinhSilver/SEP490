package com.jlas.game.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.game.dto.NekoDtos.NekoActionRequest;
import com.jlas.game.dto.NekoDtos.NekoActionResult;
import com.jlas.game.dto.NekoDtos.NekoStartRequest;
import com.jlas.game.dto.NekoDtos.NekoStateView;
import com.jlas.game.service.NekoCatchService;
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

/** API trong trận Neko Catch. Base path: /api/games/neko/{sessionId} */
@RestController
@RequestMapping("/api/games/neko/{sessionId}")
@RequiredArgsConstructor
public class NekoCatchController {
    private final NekoCatchService nekoCatchService;

    @GetMapping
    public ResponseEntity<ApiResponse<NekoStateView>> state(@PathVariable String sessionId,
                                                            @RequestParam(defaultValue = "7") int choiceLimit,
                                                            @AuthenticationPrincipal UserPrincipal user) {
        return ok(nekoCatchService.state(sessionId, user.userId(), choiceLimit));
    }

    @PostMapping("/catch")
    public ResponseEntity<ApiResponse<NekoActionResult>> catchChoice(@PathVariable String sessionId,
                                                                     @Valid @RequestBody NekoActionRequest request,
                                                                     @AuthenticationPrincipal UserPrincipal user) {
        return ok(nekoCatchService.catchChoice(sessionId, user.userId(), request.roundId(), request.choiceId()));
    }

    @PostMapping("/miss")
    public ResponseEntity<ApiResponse<NekoActionResult>> miss(@PathVariable String sessionId,
                                                              @Valid @RequestBody NekoActionRequest request,
                                                              @AuthenticationPrincipal UserPrincipal user) {
        return ok(nekoCatchService.miss(sessionId, user.userId(), request.roundId()));
    }

    @PostMapping("/bonus")
    public ResponseEntity<ApiResponse<NekoActionResult>> bonus(@PathVariable String sessionId,
                                                               @Valid @RequestBody NekoActionRequest request,
                                                               @AuthenticationPrincipal UserPrincipal user) {
        return ok(nekoCatchService.bonus(sessionId, user.userId(), request.roundId()));
    }

    @PostMapping("/time-up")
    public ResponseEntity<ApiResponse<NekoStateView>> timeUp(@PathVariable String sessionId,
                                                             @RequestParam(defaultValue = "7") int choiceLimit,
                                                             @AuthenticationPrincipal UserPrincipal user) {
        nekoCatchService.timeUp(sessionId, user.userId());
        return ok(nekoCatchService.state(sessionId, user.userId(), choiceLimit));
    }

    @PostMapping("/start")
    public ResponseEntity<ApiResponse<NekoStateView>> start(@PathVariable String sessionId,
                                                            @RequestParam(defaultValue = "7") int choiceLimit,
                                                            @RequestBody(required = false) NekoStartRequest request,
                                                            @AuthenticationPrincipal UserPrincipal user) {
        nekoCatchService.start(sessionId, user.userId(), request);
        return ok(nekoCatchService.state(sessionId, user.userId(), choiceLimit));
    }

    @PostMapping("/pause")
    public ResponseEntity<ApiResponse<NekoStateView>> pause(@PathVariable String sessionId,
                                                            @RequestParam(defaultValue = "7") int choiceLimit,
                                                            @AuthenticationPrincipal UserPrincipal user) {
        nekoCatchService.setPaused(sessionId, user.userId(), true);
        return ok(nekoCatchService.state(sessionId, user.userId(), choiceLimit));
    }

    @PostMapping("/resume")
    public ResponseEntity<ApiResponse<NekoStateView>> resume(@PathVariable String sessionId,
                                                             @RequestParam(defaultValue = "7") int choiceLimit,
                                                             @AuthenticationPrincipal UserPrincipal user) {
        nekoCatchService.setPaused(sessionId, user.userId(), false);
        return ok(nekoCatchService.state(sessionId, user.userId(), choiceLimit));
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.ok("OK", data));
    }
}
