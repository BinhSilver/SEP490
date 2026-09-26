package com.jlas.mission.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.mission.dto.CelebrationAckRequest;
import com.jlas.mission.dto.CelebrationResponse;
import com.jlas.mission.dto.ClaimMissionResponse;
import com.jlas.mission.dto.DailyGoalProgress;
import com.jlas.mission.dto.FreezeActionResponse;
import com.jlas.mission.dto.LearningTargetsRequest;
import com.jlas.mission.dto.MissionDashboardResponse;
import com.jlas.mission.dto.MissionSummaryResponse;
import com.jlas.mission.dto.TimeZoneRequest;
import com.jlas.mission.dto.UseFreezeRequest;
import com.jlas.mission.service.CreditAwardService;
import com.jlas.mission.service.DailyGoalService;
import com.jlas.mission.service.DailyMissionService;
import com.jlas.mission.service.MissionService;
import com.jlas.mission.service.StreakService;
import com.jlas.mission.service.UserZoneService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learner API for daily goal, missions, streak, freezes and reward celebrations. Base path: /api/mission.
 * Credit balance and history live in {@code /api/credits/me}.
 */
@RestController
@RequestMapping("/api/mission")
@RequiredArgsConstructor
public class MissionController {

    private final MissionService missionService;
    private final DailyGoalService dailyGoal;
    private final DailyMissionService missions;
    private final StreakService streaks;
    private final CreditAwardService awards;
    private final UserZoneService zones;

    @GetMapping("/summary")
    public ApiResponse<MissionSummaryResponse> summary(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", missionService.summary(user.userId()));
    }

    @GetMapping("/dashboard")
    public ApiResponse<MissionDashboardResponse> dashboard(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", missionService.dashboard(user.userId()));
    }

    @PutMapping("/targets")
    public ApiResponse<DailyGoalProgress> updateTargets(@AuthenticationPrincipal UserPrincipal user,
                                                        @Valid @RequestBody LearningTargetsRequest request) {
        DailyGoalProgress progress = dailyGoal.updateTargets(user.userId(), request);
        awards.applyOpenAwards(user.userId());
        return ApiResponse.ok("Đã lưu mục tiêu", progress);
    }

    @PutMapping("/timezone")
    public ApiResponse<Map<String, String>> updateTimeZone(@AuthenticationPrincipal UserPrincipal user,
                                                          @Valid @RequestBody TimeZoneRequest request) {
        return ApiResponse.ok("OK", Map.of("zoneId", zones.update(user.userId(), request.zoneId()).getId()));
    }

    @PostMapping("/missions/{code}/claim")
    public ApiResponse<ClaimMissionResponse> claim(@AuthenticationPrincipal UserPrincipal user,
                                                   @PathVariable String code) {
        ClaimMissionResponse result = missions.claim(user.userId(), code);
        return ApiResponse.ok(result.alreadyClaimed() ? "Bạn đã nhận thưởng này rồi" : "Đã nhận thưởng", result);
    }

    @PostMapping("/streak/freeze/use")
    public ApiResponse<FreezeActionResponse> useFreeze(@AuthenticationPrincipal UserPrincipal user,
                                                       @RequestBody(required = false) UseFreezeRequest request) {
        FreezeActionResponse result = streaks.useFreeze(user.userId(), request != null && request.purchase());
        awards.applyOpenAwards(user.userId());
        return ApiResponse.ok("Chuỗi đã được bảo vệ", result);
    }

    @PostMapping("/streak/freeze/decline")
    public ApiResponse<FreezeActionResponse> declineFreeze(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", streaks.declineFreeze(user.userId()));
    }

    @GetMapping("/celebrations")
    public ApiResponse<List<CelebrationResponse>> celebrations(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", missionService.celebrations(user.userId()));
    }

    @PostMapping("/celebrations/ack")
    public ApiResponse<Map<String, Integer>> acknowledge(@AuthenticationPrincipal UserPrincipal user,
                                                         @Valid @RequestBody CelebrationAckRequest request) {
        return ApiResponse.ok("OK", Map.of("acknowledged", missionService.acknowledge(user.userId(), request.ids())));
    }
}
