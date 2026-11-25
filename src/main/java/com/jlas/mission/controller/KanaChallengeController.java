package com.jlas.mission.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.mission.dto.KanaChallengeDtos.*;
import com.jlas.mission.service.KanaChallengeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mission/kana-connect")
@RequiredArgsConstructor
public class KanaChallengeController {
    private final KanaChallengeService service;

    /** level: N5..N1 or MIX; omitted opens the learner's learning-path level. */
    @GetMapping("/today")
    public ResponseEntity<ApiResponse<Today>> today(@AuthenticationPrincipal UserPrincipal user,
                                                    @RequestParam(required = false) String level) {
        return ResponseEntity.ok(ApiResponse.ok("Thử thách tuần này", service.today(user.userId(), level)));
    }

    /** Reveals the kana in the empty slot the learner picked; available every 30 seconds of play. */
    @PostMapping("/hint")
    public ResponseEntity<ApiResponse<Today>> hint(@AuthenticationPrincipal UserPrincipal user,
                                                   @Valid @RequestBody HintPick request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã mở một chữ", service.hint(user.userId(), request.challengeId(),
                request.wordId(), request.index())));
    }

    /** Bắt đầu / Tiếp tục: starts this board's clock and returns it with its kana. */
    @PostMapping("/start")
    public ResponseEntity<ApiResponse<Today>> start(@AuthenticationPrincipal UserPrincipal user,
                                                    @Valid @RequestBody HintRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Bắt đầu tính giờ", service.start(user.userId(), request.challengeId())));
    }

    /** Stops this board's clock while the learner is away; pressing Tiếp tục starts it again. */
    @PostMapping("/pause")
    public ResponseEntity<ApiResponse<Void>> pause(@AuthenticationPrincipal UserPrincipal user,
                                                   @Valid @RequestBody HintRequest request) {
        service.pause(user.userId(), request.challengeId());
        return ResponseEntity.ok(ApiResponse.ok("Đã tạm dừng", null));
    }

    @GetMapping("/leaderboard")
    public ResponseEntity<ApiResponse<Leaderboard>> leaderboard(@AuthenticationPrincipal UserPrincipal user,
                                                                @RequestParam(required = false) String level) {
        return ResponseEntity.ok(ApiResponse.ok("Bảng xếp hạng hôm nay", service.leaderboard(user.userId(), level)));
    }

    @PostMapping("/submit")
    public ResponseEntity<ApiResponse<Result>> submit(@AuthenticationPrincipal UserPrincipal user,
                                                      @Valid @RequestBody Attempt attempt) {
        Result result = service.submit(user.userId(), attempt);
        return ResponseEntity.ok(ApiResponse.ok(result.correct() ? "Đã tìm được từ" : "Thử một đường nối khác nhé", result));
    }
}
