package com.jlas.game.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.game.dto.PuzzleDtos.AnswerAction;
import com.jlas.game.dto.PuzzleDtos.AnswerReply;
import com.jlas.game.dto.PuzzleDtos.InkAction;
import com.jlas.game.dto.PuzzleDtos.InkDelta;
import com.jlas.game.dto.PuzzleDtos.RoundAction;
import com.jlas.game.dto.PuzzleDtos.View;
import com.jlas.game.service.impl.PuzzleGameService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/games/puzzles/{sessionId}")
public class PuzzleGameController {
    private final PuzzleGameService game;

    @GetMapping
    public ResponseEntity<ApiResponse<View>> state(@AuthenticationPrincipal UserPrincipal user, @PathVariable String sessionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok("OK", game.state(user.userId(), sessionId)));
    }

    @PostMapping("/answer")
    public ResponseEntity<ApiResponse<AnswerReply>> answer(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String sessionId, @Valid @RequestBody AnswerAction action) {
        return ResponseEntity.ok(ApiResponse.ok("OK", game.answer(user.userId(), sessionId, action)));
    }

    @PostMapping("/skip")
    public ResponseEntity<ApiResponse<Void>> skip(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String sessionId, @Valid @RequestBody RoundAction action) {
        game.skip(user.userId(), sessionId, action.round());
        return ResponseEntity.ok(ApiResponse.ok("OK", null));
    }

    @PostMapping("/next")
    public ResponseEntity<ApiResponse<Void>> ready(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String sessionId, @Valid @RequestBody RoundAction action) {
        game.ready(user.userId(), sessionId, action.round());
        return ResponseEntity.ok(ApiResponse.ok("OK", null));
    }

    @PostMapping("/ink")
    public ResponseEntity<ApiResponse<InkDelta>> ink(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String sessionId, @Valid @RequestBody InkAction action) {
        return ResponseEntity.ok(ApiResponse.ok("OK", game.ink(user.userId(), sessionId, action)));
    }

    @GetMapping("/images/{round}/{name}")
    public ResponseEntity<Resource> image(@AuthenticationPrincipal UserPrincipal user, @PathVariable String sessionId,
            @PathVariable int round, @PathVariable String name) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("image/webp"))
                .body(game.image(user.userId(), sessionId, round, name));
    }
}
