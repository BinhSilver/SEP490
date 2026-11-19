package com.jlas.jlptpath.dev;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.dto.JpathCheckResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [JLAS-W0108] Demo tools for the Learning paths ("Demo Learning" panel). Only registered when
 * {@code mission.dev-tools=true}; the user always comes from the principal.
 */
@RestController
@RequestMapping("/api/dev/learning")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mission", name = "dev-tools", havingValue = "true")
public class JpathDemoController {

    private final JpathCheckDemoService checkDemo;
    private final JpathStudyDemoService studyDemo;

    public record FinishRequest(@NotNull JpathCheckDemoService.Mode mode) {
    }

    /** The caller's open check (null data when none) and whether this account may use the write buttons. */
    @GetMapping("/state")
    public ApiResponse<Map<String, Object>> state(@AuthenticationPrincipal UserPrincipal user) {
        Map<String, Object> out = new HashMap<>();
        out.put("protectedAccount", user.userId() == JpathCheckDemoService.PROTECTED_USER_ID);
        out.put("openCheck", checkDemo.openCheck(user.userId()));
        return ApiResponse.ok("OK", out);
    }

    @PostMapping("/checks/{token}/finish")
    public ApiResponse<JpathCheckResult> finish(@PathVariable("token") String token,
                                                @Valid @RequestBody FinishRequest request,
                                                @AuthenticationPrincipal UserPrincipal user) {
        String message = switch (request.mode()) {
            case PASS -> "Đã trả lời đúng hết và nộp bài";
            case FAIL -> "Đã trả lời sai hết và nộp bài";
            case TIMEOUT -> "Đã để hết giờ mọi câu và nộp bài";
        };
        return ApiResponse.ok(message, checkDemo.finish(user.userId(), token, request.mode()));
    }

    /** Goes through the card steps of the caller's study session up to its next check (or its end). */
    @PostMapping("/sessions/{sessionId}/finish-cards")
    public ApiResponse<JpathStudyDemoService.Outcome> finishCards(@PathVariable("sessionId") Long sessionId,
                                                                  @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("Đã học hết các thẻ", studyDemo.finishCards(user.userId(), sessionId));
    }
}
