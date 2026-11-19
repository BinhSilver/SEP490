package com.jlas.cpath.dev;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.cpath.CpathService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * [JLAS-W0108] Demo tools only: marks the next undone step of a situation of the caller's own communication path
 * through the real {@link CpathService#step}, which checks the path, the gates, CP and the day/topic rewards. The
 * speaking score is never filled in: SPEAK only counts as a done step, so a topic still needs real recordings to pass.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mission", name = "dev-tools", havingValue = "true")
public class CpathDemoService {

    static final long PROTECTED_USER_ID = 10186L;
    static final List<String> STEPS = List.of("WORDS", "LISTEN", "ROLEPLAY", "SPEAK");

    private final CpathService cpath;

    @SuppressWarnings("unchecked")
    public Map<String, Object> nextStep(Long userId, String code, boolean roleplayRight) {
        if (userId != null && userId == PROTECTED_USER_ID) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "Không dùng nút demo trên tài khoản chính. Hãy đăng nhập tài khoản demo.");
        }
        Map<String, Object> situation = cpath.situation(userId, code);
        Object stepsObj = situation.get("steps");
        Map<String, Object> steps = stepsObj instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String next = STEPS.stream().filter(s -> !Boolean.TRUE.equals(steps.get(s.toLowerCase(Locale.ROOT))))
                .findFirst().orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "Tình huống này đã xong cả 4 bước."));
        Map<String, Object> body = null;
        if ("ROLEPLAY".equals(next)) {
            int turns = turns(situation);
            body = new LinkedHashMap<>();
            body.put("total", turns);
            body.put("correct", roleplayRight ? turns : 0);
        }
        Map<String, Object> out = new LinkedHashMap<>(cpath.step(userId, code, next, body));
        out.put("stepDone", next);
        return out;
    }

    /** Role-play turns of the situation: the learner lines, at least 1. */
    static int turns(Map<String, Object> situation) {
        Object lines = situation.get("lines");
        if (!(lines instanceof List<?> list)) {
            return 1;
        }
        long learner = list.stream().filter(l -> l instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("learner"))).count();
        return (int) Math.max(1, Math.min(50, learner));
    }
}
