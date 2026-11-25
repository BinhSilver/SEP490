package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.dto.JpathAutoPaceView;
import com.jlas.jlptpath.service.impl.JpathAutoPaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** [JLAS-W0108] F237: today's words raised after missed study days (path page card) and its "Hoàn tác". */
@RestController
@RequestMapping("/api/learning/jlpt/auto-pace")
@RequiredArgsConstructor
public class JpathAutoPaceController {

    private final JpathAutoPaceService autoPace;

    @GetMapping
    public ResponseEntity<ApiResponse<JpathAutoPaceView>> today(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", autoPace.view(user.userId())));
    }

    @PostMapping("/off")
    public ResponseEntity<ApiResponse<JpathAutoPaceView>> turnOff(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("Đã giữ số từ mỗi ngày như bạn chọn.",
                autoPace.turnOff(user.userId())));
    }
}
