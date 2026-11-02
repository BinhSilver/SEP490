package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.dto.JpathEntryPreferenceRequest;
import com.jlas.jlptpath.dto.JpathEntryStatusView;
import com.jlas.jlptpath.service.JpathPathService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** [JLAS-W0108] Entry status and survey answers of the JLPT path. */
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

    @PutMapping("/entry-preferences")
    public ResponseEntity<ApiResponse<JpathEntryStatusView>> saveEntryPreferences(
            @AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody JpathEntryPreferenceRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã lưu lựa chọn.",
                pathService.saveEntryPreference(user.userId(), request)));
    }
}
