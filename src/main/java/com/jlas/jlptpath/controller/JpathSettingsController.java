package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.jlptpath.dto.JpathLowerLevelRequest;
import com.jlas.jlptpath.dto.JpathPaceView;
import com.jlas.jlptpath.dto.JpathPathView;
import com.jlas.jlptpath.dto.JpathRedirectView;
import com.jlas.jlptpath.dto.JpathRestartRequest;
import com.jlas.jlptpath.dto.JpathSettingsUpdateRequest;
import com.jlas.jlptpath.dto.JpathSettingsView;
import com.jlas.jlptpath.dto.JpathSliderAdjustmentView;
import com.jlas.jlptpath.service.JpathDailyPlanService;
import com.jlas.jlptpath.service.JpathPathService;
import com.jlas.jlptpath.service.JpathSettingsService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [JLAS-W0108] Settings of the JLPT path: target, exam date, weekly schedule, display and reminders, and the "Đổi lộ
 * trình" block (study a lower level, restart, pause to change the goal).
 */
@RestController
@RequestMapping("/api/learning/jlpt")
@RequiredArgsConstructor
public class JpathSettingsController {

    private static final String RESTART_REDIRECT = "/survey?edit=1";
    private static final String GOAL_CHANGE_REDIRECT = "/survey?edit=goal";

    private final JpathSettingsService settingsService;
    private final JpathPathService pathService;
    private final JpathDailyPlanService dailyPlans;

    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<JpathSettingsView>> get(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", settingsService.get(user.userId())));
    }

    /** [JLAS-W0108] F151: Read-only pace preview matching {@code PUT /settings} without writing or throwing on blocked. */
    @PostMapping({"/preview", "/settings/preview"})
    public ResponseEntity<ApiResponse<JpathPaceView>> preview(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody(required = false) JpathSettingsUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("OK", settingsService.preview(user.userId(), request)));
    }

    @PutMapping("/settings")
    public ResponseEntity<ApiResponse<JpathSettingsView>> update(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody JpathSettingsUpdateRequest request) {
        if (request != null && (request.skillDays() != null || request.restDays() != null
                || request.thirdSkillDay() != null)) {
            // F130: today's plan is made from the week as it was before this change (outside the settings
            // transaction, as the plan contract asks), so a rest or skill day moved onto today waits for tomorrow.
            try {
                dailyPlans.ensurePlanExists(user.userId());
            } catch (ApiException e) {
                // no active path: the update below answers for it
            }
        }
        return ResponseEntity.ok(ApiResponse.ok("Đã lưu cài đặt.", settingsService.update(user.userId(), request)));
    }

    @GetMapping("/settings/slider-history")
    public ResponseEntity<ApiResponse<List<JpathSliderAdjustmentView>>> sliderHistory(
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", settingsService.sliderHistory(user.userId())));
    }

    @PostMapping("/path/lower-level")
    public ResponseEntity<ApiResponse<JpathPathView>> lowerLevel(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody JpathLowerLevelRequest request) {
        JpathPathView view = settingsService.lowerLevel(user.userId(), request.level());
        return ResponseEntity.ok(ApiResponse.ok("Đã chuyển sang học cấp " + view.openLevel() + ".", view));
    }

    @PostMapping("/path/return-level")
    public ResponseEntity<ApiResponse<JpathPathView>> returnLevel(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody JpathLowerLevelRequest request) {
        JpathPathView view = settingsService.returnLevel(user.userId(), request.level());
        return ResponseEntity.ok(ApiResponse.ok("Đã quay lại học cấp " + view.openLevel() + ".", view));
    }

    @PostMapping("/path/restart")
    public ResponseEntity<ApiResponse<JpathRedirectView>> restart(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody JpathRestartRequest request) {
        settingsService.restart(user.userId(), request.confirmText());
        return ResponseEntity.ok(ApiResponse.ok("Đã xóa tiến độ JLPT. Hãy làm lại khảo sát.",
                new JpathRedirectView(RESTART_REDIRECT)));
    }

    @PostMapping("/path/pause")
    public ResponseEntity<ApiResponse<JpathRedirectView>> pause(@AuthenticationPrincipal UserPrincipal user) {
        pathService.pauseForGoalChange(user.userId());
        return ResponseEntity.ok(ApiResponse.ok("Đã tạm dừng lộ trình JLPT. Hãy chọn mục tiêu mới.",
                new JpathRedirectView(GOAL_CHANGE_REDIRECT)));
    }
}
