package com.jlas.pathio;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** [JLAS-W0108] F240: entry/exit of the learner's paths ({@code ?attemptId=} adds the Smart Test result preview). */
@RestController
@RequestMapping("/api/learning/path-io")
public class PathIoController {

    private final PathIoService service;

    public PathIoController(PathIoService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) Long attemptId) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.overview(user.userId(), attemptId)));
    }
}
