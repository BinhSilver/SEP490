package com.jlas.notification.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.notification.dto.NotificationFeedDto;
import com.jlas.notification.service.UserNotificationService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/notifications") @RequiredArgsConstructor
public class NotificationController {
    private final UserNotificationService notifications;

    @GetMapping
    public ResponseEntity<ApiResponse<NotificationFeedDto>> feed(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", notifications.feed(user.userId(), user.username())));
    }

    @PostMapping("/read")
    public ResponseEntity<ApiResponse<Void>> read(@RequestBody(required = false) Map<String, Long> body,
                                                   @AuthenticationPrincipal UserPrincipal user) {
        notifications.markRead(user.userId(), body == null ? null : body.get("id"));
        return ResponseEntity.ok(ApiResponse.ok("Đã đánh dấu đã đọc"));
    }
}
