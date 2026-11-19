package com.jlas.jlptpath.dev;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathAnswerRequest;
import com.jlas.jlptpath.dto.JpathCheckResult;
import com.jlas.jlptpath.dto.JpathCheckSubmitRequest;
import com.jlas.jlptpath.dto.JpathOptionView;
import com.jlas.jlptpath.dto.JpathPairPickRequest;
import com.jlas.jlptpath.dto.JpathQuestionView;
import com.jlas.jlptpath.entity.JpathCheckSet;
import com.jlas.jlptpath.repository.JpathCheckSetRepository;
import com.jlas.jlptpath.service.JpathCheckService;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * [JLAS-W0108] Demo tools only ("Demo Learning" panel): finishes the caller's own open JLPT-path check by sending
 * answers through the real {@link JpathCheckService#answer}/{@link JpathCheckService#pick} and then the real
 * {@link JpathCheckService#submit}, so scoring, progress, unlocks and CP all run as when a learner answers by hand.
 * The answer keys are read here on the server and never reach the browser.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mission", name = "dev-tools", havingValue = "true")
public class JpathCheckDemoService {

    /** The owner's own account: the demo panel never writes to it (owner rule 20/11). */
    static final long PROTECTED_USER_ID = 10186L;

    public enum Mode { PASS, FAIL, TIMEOUT }

    /** What the panel shows: the open check, or none. */
    public record OpenCheck(String token, String kind, String tab, int questions, boolean timerOn) {
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final JpathCheckService checks;
    private final JpathCheckSetRepository checkSets;
    private final EntityManager em;

    /** The caller's newest check still open (ISSUED), or null. */
    public OpenCheck openCheck(Long userId) {
        List<JpathCheckSet> rows = em.createQuery(
                        "select c from JpathCheckSet c where c.userId = :u and c.status = 'ISSUED' order by c.id desc",
                        JpathCheckSet.class)
                .setParameter("u", userId).setMaxResults(1).getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        JpathCheckSet set = rows.get(0);
        return new OpenCheck(set.getToken(), set.getKind().name(), set.getTab().name(),
                questions(set).size(), set.isTimerOn());
    }

    /** Answers every open question of the caller's check {@code token} by {@code mode}, then submits it. */
    public JpathCheckResult finish(Long userId, String token, Mode mode) {
        guard(userId);
        JpathCheckSet set = checkSets.findByTokenAndUserId(token, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_CHECK_NOT_FOUND, "Không có bài kiểm tra này."));
        if (!"ISSUED".equals(set.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Bài kiểm tra này đã nộp hoặc đã hết hạn.");
        }
        if (mode == Mode.TIMEOUT && !set.isTimerOn()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bài này không bật đồng hồ, không thử hết giờ được.");
        }
        JsonNode keys = readTree(set.getAnswersJson()).path("keys");
        JsonNode progress = readTree(set.getProgressJson());
        for (JpathQuestionView q : questions(set)) {
            JsonNode key = keys.path(q.id());
            if (q.board() != null) {
                pickBoard(userId, token, q, key, mode == Mode.PASS);
                continue;
            }
            if (progress.path("answers").has(q.id())) {
                continue; // already answered by hand
            }
            String value = mode == Mode.TIMEOUT ? "" : mode == Mode.PASS ? rightValue(q, key) : wrongValue(q, key);
            checks.answer(userId, token, new JpathAnswerRequest(q.id(), value, null));
        }
        return checks.submit(userId, token, new JpathCheckSubmitRequest("demo-" + mode.name().toLowerCase()));
    }

    static void guard(Long userId) {
        if (userId != null && userId == PROTECTED_USER_ID) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "Không dùng nút demo trên tài khoản chính. Hãy đăng nhập tài khoản demo.");
        }
    }

    private void pickBoard(Long userId, String token, JpathQuestionView q, JsonNode key, boolean right) {
        Map<String, String> pairs = JSON.convertValue(key.path("pairs"), new TypeReference<Map<String, String>>() { });
        if (pairs == null) {
            return;
        }
        String boardId = q.board().boardId();
        for (Map.Entry<String, String> pair : pairs.entrySet()) {
            if (!right) {
                // one wrong pick first, so the pair counts wrong; the right pick then closes it like a learner would
                q.board().right().stream().map(JpathOptionView::id).filter(id -> !id.equals(pair.getValue()))
                        .findFirst()
                        .ifPresent(id -> checks.pick(userId, token, new JpathPairPickRequest(boardId, pair.getKey(), id)));
            }
            checks.pick(userId, token, new JpathPairPickRequest(boardId, pair.getKey(), pair.getValue()));
        }
    }

    static String rightValue(JpathQuestionView q, JsonNode key) {
        return switch (q.type()) {
            case "ORDER" -> String.join(",", strings(key.path("order")));
            case "FILL_TEXT", "DICTATION" -> key.path("text").asText("");
            default -> key.path("correct").asText("");
        };
    }

    static String wrongValue(JpathQuestionView q, JsonNode key) {
        return switch (q.type()) {
            case "ORDER" -> {
                List<String> order = new ArrayList<>(strings(key.path("order")));
                java.util.Collections.reverse(order);
                if (order.size() > 1 && order.equals(strings(key.path("order")))) {
                    order.add(order.remove(0));
                }
                yield String.join(",", order);
            }
            case "FILL_TEXT" -> "×";
            case "DICTATION" -> "[\"×\"]";
            default -> {
                String right = key.path("correct").asText("");
                yield q.options() == null ? "×" : q.options().stream().map(JpathOptionView::id)
                        .filter(id -> !id.equals(right)).findFirst().orElse("×");
            }
        };
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
            out.add(it.next().asText());
        }
        return out;
    }

    private static List<JpathQuestionView> questions(JpathCheckSet set) {
        try {
            return JSON.readValue(set.getQuestionsJson(), new TypeReference<List<JpathQuestionView>>() { });
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Không đọc được câu hỏi của bài kiểm tra.");
        }
    }

    private static JsonNode readTree(String json) {
        try {
            return json == null ? JSON.createObjectNode() : JSON.readTree(json);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }
}
