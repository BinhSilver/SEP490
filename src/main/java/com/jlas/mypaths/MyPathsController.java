package com.jlas.mypaths;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [JLAS-W0108] Owner 24/11 "Lộ trình của tôi" API. Resuming a third path answers 409 MY_PATHS_FULL with
 * {@code data.active} (the two running paths); the page asks which to pause and retries with {@code {pauseKey}}.
 * Notifications go out after the service transaction, so a failing bell never undoes the action.
 */
@RestController
@RequestMapping("/api/learning/my-paths")
public class MyPathsController {

    private final MyPathsService service;
    private final MyPathsNotifier notifier;

    public MyPathsController(MyPathsService service, MyPathsNotifier notifier) {
        this.service = service;
        this.notifier = notifier;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.overview(user.userId())));
    }

    @PostMapping("/{key}/pause")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pause(@AuthenticationPrincipal UserPrincipal user, @PathVariable String key) {
        String title = service.pause(user.userId(), key);
        notifier.paused(user.userId(), title, key + "-" + System.currentTimeMillis());
        return ResponseEntity.ok(ApiResponse.ok("Đã tạm dừng " + title + ".", service.overview(user.userId())));
    }

    @PostMapping("/{key}/resume")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resume(@AuthenticationPrincipal UserPrincipal user, @PathVariable String key,
            @RequestBody(required = false) Map<String, Object> body) {
        Object pk = body == null ? null : body.get("pauseKey");
        String pauseKey = pk == null || String.valueOf(pk).isBlank() ? null : String.valueOf(pk);
        String pausedTitle = pauseKey == null ? null : service.titleOf(user.userId(), pauseKey);
        String title;
        try {
            title = service.resume(user.userId(), key, pauseKey);
        } catch (ApiException e) {
            if (e.getErrorCode() != ErrorCode.MY_PATHS_FULL) throw e;
            ApiResponse<Map<String, Object>> full = ApiResponse.<Map<String, Object>>builder().success(false)
                    .code(ErrorCode.MY_PATHS_FULL.name()).message(ErrorCode.MY_PATHS_FULL.getDefaultMessage())
                    .data(Map.of("active", service.activePaths(user.userId()))).timestamp(Instant.now()).build();
            return ResponseEntity.status(HttpStatus.CONFLICT).body(full);
        }
        String stamp = key + "-" + System.currentTimeMillis();
        if (pausedTitle != null) notifier.paused(user.userId(), pausedTitle, pauseKey + "-" + stamp);
        notifier.resumed(user.userId(), title, null, stamp);
        return ResponseEntity.ok(ApiResponse.ok(title + " học tiếp.", service.overview(user.userId())));
    }
}
