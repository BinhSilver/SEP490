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
        return ResponseEntity.ok(ApiResponse.ok("Thử thách hôm nay", service.today(user.userId(), level)));
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
