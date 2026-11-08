package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.dto.JpathConfirmationView;
import com.jlas.jlptpath.dto.JpathCreateRequest;
import com.jlas.jlptpath.dto.JpathEntryPreferenceRequest;
import com.jlas.jlptpath.dto.JpathEntryStatusView;
import com.jlas.jlptpath.dto.JpathEstimateRequest;
import com.jlas.jlptpath.dto.JpathLevelPreviewView;
import com.jlas.jlptpath.dto.JpathModeRequest;
import com.jlas.jlptpath.dto.JpathPaceView;
import com.jlas.jlptpath.dto.JpathPathView;
import com.jlas.jlptpath.dto.JpathSliderUpdateRequest;
import com.jlas.jlptpath.dto.JpathSliderView;
import com.jlas.jlptpath.enums.JpathEntrySource;
import com.jlas.jlptpath.service.JpathPathService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * [JLAS-W0108] Entry status, survey answers, confirmation screen, pace estimate, path creation, the path view, level
 * previews, sliders, Rebalance and the study mode of the JLPT path.
 */
@RestController
@RequestMapping("/api/learning/jlpt")
@RequiredArgsConstructor
public class JpathPathController {

    private final JpathPathService pathService;

    @GetMapping("/entry-status")
    public ResponseEntity<ApiResponse<JpathEntryStatusView>> entryStatus(
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", pathService.entryStatus(user.userId())));
    }

    @GetMapping("/confirmation")
    public ResponseEntity<ApiResponse<JpathConfirmationView>> confirmation(
            @AuthenticationPrincipal UserPrincipal user, @RequestParam JpathEntrySource source,
            @RequestParam(required = false) Long assessmentAttemptId) {
        return ResponseEntity.ok(ApiResponse.ok("OK",
                pathService.confirmation(user.userId(), source, assessmentAttemptId)));
    }

    @PostMapping("/estimate")
    public ResponseEntity<ApiResponse<JpathPaceView>> estimate(
            @AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody JpathEstimateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("OK", pathService.estimate(user.userId(), request)));
    }

    @PutMapping("/entry-preferences")
    public ResponseEntity<ApiResponse<JpathEntryStatusView>> saveEntryPreferences(
            @AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody JpathEntryPreferenceRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã lưu lựa chọn.",
                pathService.saveEntryPreference(user.userId(), request)));
    }

    @PostMapping("/paths")
    public ResponseEntity<ApiResponse<JpathPathView>> create(
            @AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody JpathCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã tạo lộ trình.", pathService.create(user.userId(), request)));
    }

    @GetMapping("/path")
    public ResponseEntity<ApiResponse<JpathPathView>> current(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", pathService.current(user.userId())));
    }

    @GetMapping("/levels/{level}/preview")
    public ResponseEntity<ApiResponse<JpathLevelPreviewView>> levelPreview(
            @AuthenticationPrincipal UserPrincipal user, @PathVariable String level) {
        return ResponseEntity.ok(ApiResponse.ok("OK", pathService.levelPreview(user.userId(), level)));
    }

    @PutMapping("/path/sliders")
    public ResponseEntity<ApiResponse<JpathSliderView>> updateSliders(@AuthenticationPrincipal UserPrincipal user,
            @Valid @RequestBody JpathSliderUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật lộ trình.",
                pathService.updateSliders(user.userId(), request)));
    }

    @PostMapping("/path/rebalance")
    public ResponseEntity<ApiResponse<JpathPaceView>> rebalance(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cân lại số mục mỗi ngày.", pathService.rebalance(user.userId())));
    }

    @PutMapping("/path/mode")
    public ResponseEntity<ApiResponse<JpathPathView>> changeMode(@AuthenticationPrincipal UserPrincipal user,
            @Valid @RequestBody JpathModeRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã đổi cách học.",
                pathService.changeMode(user.userId(), request.mode())));
    }
}
