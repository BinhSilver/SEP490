package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.dto.JpathSettingsUpdateRequest;
import com.jlas.jlptpath.dto.JpathSettingsView;
import com.jlas.jlptpath.dto.JpathSliderAdjustmentView;
import com.jlas.jlptpath.service.JpathSettingsService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** [JLAS-W0108] Settings of the JLPT path: target, exam date, weekly schedule, display and reminders. */
@RestController
@RequestMapping("/api/learning/jlpt")
@RequiredArgsConstructor
public class JpathSettingsController {

    private final JpathSettingsService settingsService;

    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<JpathSettingsView>> get(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", settingsService.get(user.userId())));
    }

    @PutMapping("/settings")
    public ResponseEntity<ApiResponse<JpathSettingsView>> update(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody JpathSettingsUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã lưu cài đặt.", settingsService.update(user.userId(), request)));
    }

    @GetMapping("/settings/slider-history")
    public ResponseEntity<ApiResponse<List<JpathSliderAdjustmentView>>> sliderHistory(
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", settingsService.sliderHistory(user.userId())));
    }
}
