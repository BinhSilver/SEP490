package com.jlas.notification.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.notification.dto.NotificationFeedDto;
import com.jlas.notification.dto.NotificationPageDto;
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
        return ResponseEntity.ok(ApiResponse.ok("OK", notifications.feed(user.userId())));
    }

    /** Lightweight poll for the bell badge; the full feed is loaded only when the panel opens. */
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> unreadCount(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", Map.of("unreadCount", notifications.unreadCount(user.userId()))));
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<NotificationPageDto>> history(@AuthenticationPrincipal UserPrincipal user,
                                                                    @RequestParam(defaultValue = "false") boolean unreadOnly,
                                                                    @RequestParam(defaultValue = "0") int page,
                                                                    @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok("OK", notifications.history(user.userId(), unreadOnly, page, size)));
    }

    @PostMapping("/read")
    public ResponseEntity<ApiResponse<Void>> read(@RequestBody(required = false) Map<String, Long> body,
                                                   @AuthenticationPrincipal UserPrincipal user) {
        notifications.markRead(user.userId(), body == null ? null : body.get("id"));
        return ResponseEntity.ok(ApiResponse.ok("Đã đánh dấu đã đọc"));
    }
}
