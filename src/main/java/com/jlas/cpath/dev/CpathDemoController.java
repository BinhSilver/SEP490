package com.jlas.cpath.dev;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** [JLAS-W0108] Demo tools of the communication path. Only registered when {@code mission.dev-tools=true}. */
@RestController
@RequestMapping("/api/dev/learning/comm")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mission", name = "dev-tools", havingValue = "true")
public class CpathDemoController {

    private final CpathDemoService demo;

    public record NextStepRequest(boolean roleplayRight) {
    }

    @PostMapping("/situations/{code}/next-step")
    public ApiResponse<Map<String, Object>> nextStep(@PathVariable("code") String code,
                                                     @RequestBody(required = false) NextStepRequest request,
                                                     @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("Đã hoàn tất bước tiếp theo",
                demo.nextStep(user.userId(), code, request == null || request.roleplayRight()));
    }
}
