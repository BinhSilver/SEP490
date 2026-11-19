package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.ai.AiUsageRecorder;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathAnsweredView;
import com.jlas.jlptpath.dto.JpathCheckView;
import com.jlas.jlptpath.dto.JpathOptionView;
import com.jlas.jlptpath.dto.JpathQuestionView;
import com.jlas.jlptpath.dto.JpathSkillCheckView;
import com.jlas.jlptpath.service.JpathCheckService;
import com.jlas.jlptpath.service.impl.JpathAiExplainAllowance.Charge;
import com.jlas.jlptpath.service.impl.JpathAiExplainAllowance.ChargeKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * [JLAS-W0108] F36: Giải thích chi tiết câu hỏi và trả lời câu hỏi thêm bằng AI (OpenRouter) cho Lộ trình JLPT
 * và Lộ trình Giao tiếp.
 * <ul>
 *   <li>Với câu thuộc {@code JpathCheck} / {@code JpathSkill}, máy chủ tự đọc {@link JpathCheckService#get} để xác minh
 *       câu hỏi đã được khóa đáp án trước khi giải thích (không bao giờ làm lộ đáp án câu chưa làm).</li>
 *   <li>Lời giải đầu tiên được đệm theo SHA-256 của nội dung câu + đáp án đã chọn để tiết kiệm chi phí API.</li>
 *   <li>Chỉ trừ lượt khi nhận được phản hồi hợp lệ; lỗi mạng / API tự hoàn lượt qua {@link JpathAiExplainAllowance#release}.</li>
 * </ul>
 */
@Service
public class JpathAiExplainService {
    private static final Logger log = LoggerFactory.getLogger(JpathAiExplainService.class);
    static final String USAGE_FEATURE = "LEARNING_AI_EXPLAIN";
    public static final int MAX_FOLLOW_UP_LEN = 300;
    public static final int MAX_HISTORY_MESSAGES = 8;

    private static final String SYS_EXPLAIN = """
            Bạn là gia sư tiếng Nhật tận tâm cho học viên Việt Nam trên hệ thống KotsuKotsu.
            Hãy giải thích câu hỏi luyện tập dưới đây bằng tiếng Việt rõ ràng, dễ hiểu, súc tích, bám sát trình độ câu hỏi.
            Trả về ĐÚNG một đối tượng JSON hợp lệ (không bọc ```json, không viết chữ bên ngoài JSON) theo cấu trúc:
            {
              "summaryVi": "1-2 câu kết luận ngắn gọn vì sao đáp án đúng là chính xác.",
              "whyRightVi": "Giải thích chi tiết ngữ nghĩa, ngữ pháp hoặc ngữ cảnh của đáp án đúng.",
              "whyWrongVi": "Giải thích vì sao phương án người học chọn chưa đúng (nếu người học đã làm đúng thì nêu ngắn điểm dễ nhầm của các phương án nhiễu).",
              "keyPoints": [
                {"term": "từ/mẫu câu tiếng Nhật", "reading": "cách đọc hiragana", "meaningVi": "nghĩa tiếng Việt", "noteVi": "ghi chú ngắn cách dùng"}
              ],
              "tipVi": "1 mẹo nhớ nhanh ngắn gọn."
            }
            Quy tắc: tối đa 4 mục trong keyPoints; không bịa thông tin ngoài ngữ cảnh câu hỏi.
            """;

    private static final String SYS_ASK = """
            Bạn là gia sư tiếng Nhật thân thiện trên KotsuKotsu, đang giải đáp thắc mắc tiếp nối cho học viên Việt Nam về đúng câu hỏi đang học.
            Trả lời bằng tiếng Việt ngắn gọn (khoảng 3-6 câu), đi thẳng vào trọng tâm câu hỏi của học viên, kèm ví dụ tiếng Nhật ngắn (có cách đọc trong ngoặc) khi cần.
            Không dùng bảng phức tạp; chỉ viết đoạn văn/gạch đầu dòng dễ đọc.
            """;

    private final JpathAiExplainAllowance allowance;
    private final JpathCheckService checkService;
    private final JdbcTemplate jdbc;
    private final AiUsageRecorder usageRecorder;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate;

    @Value("${openrouter.api.key:}")
    private String apiKey;

    @Value("${openrouter.api.url:https://openrouter.ai/api/v1}")
    private String apiUrl;

    @Value("${learning.ai-explain.model:${openrouter.model:openai/gpt-4o-mini}}")
    private String model;

    @Value("${openrouter.site.url:https://kotsukotsu.id.vn}")
    private String siteUrl;

    @Value("${openrouter.site.name:KotsuKotsu}")
    private String siteName;

    public JpathAiExplainService(JpathAiExplainAllowance allowance,
                                 JpathCheckService checkService,
                                 JdbcTemplate jdbc,
                                 AiUsageRecorder usageRecorder) {
        this.allowance = allowance;
        this.checkService = checkService;
        this.jdbc = jdbc;
        this.usageRecorder = usageRecorder;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);
        factory.setReadTimeout(45_000);
        this.restTemplate = new RestTemplate(factory);
        this.restTemplate.getMessageConverters().removeIf(c -> c instanceof StringHttpMessageConverter);
        this.restTemplate.getMessageConverters().add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8));
    }

    public record ExplainRequest(
            String source,
            String checkToken,
            String questionId,
            String contextText,
            String prompt,
            List<String> options,
            String userAnswer,
            String correctAnswer,
            String baseExplanation) {
    }

    public record ChatTurn(String role, String text) {
    }

    public record AskRequest(
            String source,
            String checkToken,
            String questionId,
            String contextText,
            String prompt,
            List<String> options,
            String userAnswer,
            String correctAnswer,
            String question,
            List<ChatTurn> history) {
    }

    public record KeyPoint(String term, String reading, String meaningVi, String noteVi) {
    }

    public record ExplainContent(
            String summaryVi,
            String whyRightVi,
            String whyWrongVi,
            List<KeyPoint> keyPoints,
            String tipVi) {
    }

    public record ExplainResponse(
            ExplainContent explanation,
            boolean cached,
            String chargeKind,
            String chargeNote,
            JpathAiExplainAllowance.Status allowance) {
    }

    public record AskResponse(
            String replyVi,
            String chargeKind,
            String chargeNote,
            JpathAiExplainAllowance.Status allowance) {
    }

    private record ResolvedQuestion(
            String source,
            String contextText,
            String prompt,
            List<String> options,
            String userAnswer,
            String correctAnswer,
            boolean correct,
            String baseExplanation) {
    }

    public ExplainResponse explain(Long userId, ExplainRequest req) {
        if (req == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Thiếu thông tin câu hỏi.");
        }
        ResolvedQuestion q = resolveQuestion(userId, req.source(), req.checkToken(), req.questionId(),
                req.contextText(), req.prompt(), req.options(), req.userAnswer(), req.correctAnswer(), req.baseExplanation());
        String cacheKey = computeCacheKey(q);
        Charge charge = allowance.reserve(userId, cacheKey);
        try {
            ExplainContent cached = readCache(cacheKey);
            if (cached != null) {
                allowance.commitSeen(userId, cacheKey);
                JpathAiExplainAllowance.Status st = allowance.status(userId);
                return new ExplainResponse(cached, true, charge.kind().name(), chargeNote(charge.kind(), st), st);
            }
            String raw = callOpenRouter(SYS_EXPLAIN, List.of(), buildExplainPrompt(q), true);
            ExplainContent parsed = parseExplain(raw);
            if (parsed == null) {
                throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                        "AI giải thích tạm thời chưa phản hồi. Lượt của bạn không bị trừ, hãy thử lại nhé.");
            }
            writeCache(cacheKey, q.source(), parsed);
            allowance.commitSeen(userId, cacheKey);
            JpathAiExplainAllowance.Status st = allowance.status(userId);
            return new ExplainResponse(parsed, false, charge.kind().name(), chargeNote(charge.kind(), st), st);
        } catch (RuntimeException ex) {
            allowance.release(charge);
            throw ex;
        }
    }

    public AskResponse ask(Long userId, AskRequest req) {
        if (req == null || req.question() == null || req.question().trim().isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Hãy nhập câu hỏi bạn muốn hỏi thêm.");
        }
        String followUp = req.question().trim();
        if (followUp.length() > MAX_FOLLOW_UP_LEN) {
            followUp = followUp.substring(0, MAX_FOLLOW_UP_LEN);
        }
        List<ChatTurn> history = req.history() == null ? List.of() : req.history();
        if (history.size() > MAX_HISTORY_MESSAGES) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Đã đạt giới hạn 5 câu hỏi thêm cho câu này.");
        }
        ResolvedQuestion q = resolveQuestion(userId, req.source(), req.checkToken(), req.questionId(),
                req.contextText(), req.prompt(), req.options(), req.userAnswer(), req.correctAnswer(), null);
        Charge charge = allowance.reserve(userId, null);
        try {
            List<Map<String, String>> msgs = new ArrayList<>();
            msgs.add(Map.of("role", "user", "content", "Ngữ cảnh câu đang học:\n" + buildExplainPrompt(q)));
            msgs.add(Map.of("role", "assistant", "content", "Mình đã nắm rõ câu hỏi và đáp án của bạn. Bạn muốn hỏi thêm điểm nào?"));
            for (ChatTurn turn : history) {
                if (turn == null || turn.text() == null || turn.text().isBlank()) continue;
                String role = "assistant".equalsIgnoreCase(turn.role()) ? "assistant" : "user";
                msgs.add(Map.of("role", role, "content", clip(turn.text().trim(), 800)));
            }
            String reply = callOpenRouter(SYS_ASK, msgs, followUp, false);
            if (reply == null || reply.isBlank()) {
                throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                        "AI giải đáp tạm thời chưa phản hồi. Lượt của bạn không bị trừ, hãy thử lại nhé.");
            }
            JpathAiExplainAllowance.Status st = allowance.status(userId);
            return new AskResponse(reply.trim(), charge.kind().name(), chargeNote(charge.kind(), st), st);
        } catch (RuntimeException ex) {
            allowance.release(charge);
            throw ex;
        }
    }

    private ResolvedQuestion resolveQuestion(Long userId, String rawSource, String checkToken, String questionId,
                                             String contextText, String prompt, List<String> options,
                                             String userAnswer, String correctAnswer, String baseExplanation) {
        String source = normalizeSource(rawSource);
        if (checkToken != null && !checkToken.isBlank() && questionId != null && !questionId.isBlank()) {
            JpathCheckView check = checkService.get(userId, checkToken.trim());
            JpathAnsweredView ans = (check.answered() == null ? List.<JpathAnsweredView>of() : check.answered())
                    .stream()
                    .filter(a -> a != null && Objects.equals(a.questionId(), questionId.trim()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(ErrorCode.BAD_REQUEST,
                            "Hãy chọn đáp án trước khi xem AI giải thích."));
            JpathQuestionView qv = (check.questions() == null ? List.<JpathQuestionView>of() : check.questions())
                    .stream()
                    .filter(q -> q != null && Objects.equals(q.id(), questionId.trim()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND, "Không tìm thấy câu hỏi."));

            List<String> optLabels = new ArrayList<>();
            Map<String, String> optById = new LinkedHashMap<>();
            if (qv.options() != null) {
                for (JpathOptionView o : qv.options()) {
                    if (o != null) {
                        optById.put(o.id(), o.label());
                        optLabels.add(o.label());
                    }
                }
            }
            String resolvedUser = resolveAnswerText(qv.type(), ans.value(), optById);
            String resolvedRight = resolveAnswerText(qv.type(), ans.correctValue(), optById);
            String skillContext = extractSkillContext(check, qv.id(), contextText);
            String qPrompt = buildQuestionHeading(qv);
            return new ResolvedQuestion(source, clip(skillContext, 1500), clip(qPrompt, 600),
                    optLabels, clip(resolvedUser, 300), clip(resolvedRight, 300),
                    ans.correct(), clip(ans.explanation(), 500));
        }

        if (prompt == null || prompt.isBlank() || correctAnswer == null || correctAnswer.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Thiếu đề bài hoặc đáp án để AI giải thích.");
        }
        List<String> cleanOpts = options == null ? List.of()
                : options.stream().filter(Objects::nonNull).map(s -> clip(s.trim(), 200)).limit(6).toList();
        String ua = userAnswer == null ? "" : clip(userAnswer.trim(), 300);
        String ca = clip(correctAnswer.trim(), 300);
        return new ResolvedQuestion(source, clip(contextText, 1200), clip(prompt.trim(), 600),
                cleanOpts, ua, ca, ua.equals(ca), clip(baseExplanation, 500));
    }

    private static String buildQuestionHeading(JpathQuestionView qv) {
        StringBuilder sb = new StringBuilder();
        if (qv.instructionVi() != null && !qv.instructionVi().isBlank()) {
            sb.append(qv.instructionVi().trim());
        }
        if (qv.prompt() != null && !qv.prompt().isBlank()) {
            if (!sb.isEmpty()) sb.append(" — ");
            sb.append(qv.prompt().trim());
        }
        if (qv.promptFurigana() != null && !qv.promptFurigana().isBlank()
                && !Objects.equals(qv.promptFurigana(), qv.prompt())) {
            sb.append(" (").append(qv.promptFurigana().trim()).append(")");
        }
        return sb.isEmpty() ? "Câu hỏi luyện tập" : sb.toString();
    }

    private static String resolveAnswerText(String type, String rawValue, Map<String, String> optById) {
        if (rawValue == null || rawValue.isBlank()) {
            return "Chưa trả lời / Hết giờ";
        }
        if ("ORDER".equals(type)) {
            List<String> parts = new ArrayList<>();
            for (String id : rawValue.split(",")) {
                String lbl = optById.get(id.trim());
                parts.add(lbl != null ? lbl : id.trim());
            }
            return String.join(" ", parts);
        }
        return optById.getOrDefault(rawValue, rawValue);
    }

    private static String extractSkillContext(JpathCheckView check, String qId, String fallback) {
        JpathSkillCheckView skill = check.skill();
        if (skill == null || skill.sets() == null) {
            return fallback;
        }
        for (JpathSkillCheckView.SetView set : skill.sets()) {
            if (set == null || set.questionIds() == null || !set.questionIds().contains(qId)) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            if (set.passageJa() != null && !set.passageJa().isBlank()) {
                sb.append("Đoạn văn: ").append(set.passageJa().trim());
            }
            if (set.revealed() && set.script() != null && !set.script().isEmpty()) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append("Hội thoại nghe:\n");
                for (JpathSkillCheckView.ScriptLine line : set.script()) {
                    if (line != null && line.ja() != null) {
                        sb.append(line.speaker() != null ? line.speaker() + ": " : "").append(line.ja()).append("\n");
                    }
                }
            }
            if (set.imageDescriptionVi() != null && !set.imageDescriptionVi().isBlank()) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append("Tình huống: ").append(set.imageDescriptionVi().trim());
            }
            return sb.toString().trim();
        }
        return fallback;
    }

    private static String buildExplainPrompt(ResolvedQuestion q) {
        StringBuilder sb = new StringBuilder();
        sb.append("Loại bài: ").append(q.source()).append("\n");
        if (q.contextText() != null && !q.contextText().isBlank()) {
            sb.append("Ngữ cảnh / Đoạn bài:\n").append(q.contextText()).append("\n");
        }
        sb.append("Câu hỏi: ").append(q.prompt()).append("\n");
        if (!q.options().isEmpty()) {
            sb.append("Các lựa chọn:\n");
            for (int i = 0; i < q.options().size(); i++) {
                sb.append("  ").append(i + 1).append(") ").append(q.options().get(i)).append("\n");
            }
        }
        sb.append("Học viên đã chọn: ").append(q.userAnswer().isBlank() ? "(Chưa chọn)" : q.userAnswer())
                .append(q.correct() ? " (Đúng)" : " (Sai)").append("\n");
        sb.append("Đáp án đúng: ").append(q.correctAnswer()).append("\n");
        if (q.baseExplanation() != null && !q.baseExplanation().isBlank()) {
            sb.append("Gợi ý có sẵn của hệ thống: ").append(q.baseExplanation()).append("\n");
        }
        return sb.toString();
    }

    private String callOpenRouter(String systemPrompt, List<Map<String, String>> history, String userPrompt, boolean jsonMode) {
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty() || key.contains("XXXX")) {
            log.warn("OpenRouter API key chưa cấu hình cho LEARNING_AI_EXPLAIN.");
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Máy chủ chưa cấu hình khóa AI. Lượt của bạn không bị trừ.");
        }
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.addAll(history);
        messages.add(Map.of("role", "user", "content", userPrompt));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", jsonMode ? 0.25 : 0.4);
        body.put("max_tokens", jsonMode ? 1600 : 900);
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(com.jlas.common.ai.AiFeature.LEARNING_AI_EXPLAIN.apiKey(key));
        headers.set("HTTP-Referer", siteUrl);
        headers.set("X-Title", siteName);

        String endpoint = apiUrl == null ? "https://openrouter.ai/api/v1" : apiUrl.trim();
        if (!endpoint.endsWith("/chat/completions")) {
            endpoint = endpoint.endsWith("/") ? endpoint + "chat/completions" : endpoint + "/chat/completions";
        }
        try {
            ResponseEntity<String> res = restTemplate.exchange(
                    endpoint, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = mapper.readValue(res.getBody(), Map.class);
            usageRecorder.recordOpenRouter(com.jlas.common.ai.AiFeature.LEARNING_AI_EXPLAIN, model, root);
            Object choicesObj = root.get("choices");
            if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
                return null;
            }
            Object first = choices.getFirst();
            if (!(first instanceof Map<?, ?> choiceMap)) {
                return null;
            }
            Object msgObj = choiceMap.get("message");
            if (!(msgObj instanceof Map<?, ?> msgMap)) {
                return null;
            }
            Object content = msgMap.get("content");
            return content == null ? null : String.valueOf(content);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("OpenRouter call failed for LEARNING_AI_EXPLAIN: {}", ex.getMessage());
            return null;
        }
    }

    ExplainContent parseExplain(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int nl = trimmed.indexOf('\n');
            if (nl > 0) trimmed = trimmed.substring(nl + 1);
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            String candidate = trimmed.substring(braceStart, braceEnd + 1);
            try {
                JsonNode node = mapper.readTree(candidate);
                if (node != null && node.isObject()) {
                    String summary = textOrEmpty(node, "summaryVi");
                    String whyRight = textOrEmpty(node, "whyRightVi");
                    String whyWrong = textOrEmpty(node, "whyWrongVi");
                    String tip = textOrEmpty(node, "tipVi");
                    List<KeyPoint> points = new ArrayList<>();
                    JsonNode arr = node.get("keyPoints");
                    if (arr != null && arr.isArray()) {
                        for (JsonNode item : arr) {
                            if (item != null && item.isObject()) {
                                String term = textOrEmpty(item, "term");
                                String meaning = textOrEmpty(item, "meaningVi");
                                if (!term.isEmpty() || !meaning.isEmpty()) {
                                    points.add(new KeyPoint(term, textOrEmpty(item, "reading"),
                                            meaning, textOrEmpty(item, "noteVi")));
                                }
                            }
                            if (points.size() >= 4) break;
                        }
                    }
                    if (!summary.isEmpty() || !whyRight.isEmpty()) {
                        return new ExplainContent(summary, whyRight, whyWrong, points, tip);
                    }
                }
            } catch (Exception ignored) {
                // fall back to plain text wrapper below if model returned non-JSON prose
            }
        }
        return new ExplainContent(clip(trimmed, 1200), "", "", List.of(), "");
    }

    private ExplainContent readCache(String cacheKey) {
        List<String> rows = jdbc.queryForList(
                "SELECT answer_json FROM jpath_ai_explain_cache WHERE cache_key = ?", String.class, cacheKey);
        if (rows.isEmpty()) return null;
        try {
            return mapper.readValue(rows.getFirst(), ExplainContent.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private void writeCache(String cacheKey, String source, ExplainContent content) {
        try {
            String json = mapper.writeValueAsString(content);
            jdbc.update("INSERT INTO jpath_ai_explain_cache (cache_key, source, answer_json, model, created_at) "
                    + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6)) "
                    + "ON DUPLICATE KEY UPDATE answer_json = VALUES(answer_json), model = VALUES(model)",
                    cacheKey, source, json, clip(model, 120));
        } catch (Exception ex) {
            log.warn("Could not write jpath_ai_explain_cache: {}", ex.getMessage());
        }
    }

    static String computeCacheKey(ResolvedQuestion q) {
        String canonical = String.join("\u001f",
                q.source(),
                q.contextText() == null ? "" : q.contextText(),
                q.prompt(),
                String.join("\u001e", q.options()),
                q.correctAnswer(),
                q.userAnswer());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String chargeNote(ChargeKind kind, JpathAiExplainAllowance.Status st) {
        return switch (kind) {
            case TRIAL -> "Đã dùng 1 lượt dùng thử miễn phí đầu tiên · Các lượt sau: " + st.packPrice() + " CP/lượt";
            case REPEAT_TODAY -> "Xem lại câu đã mở hôm nay (không tốn lượt)";
            case PRO_DAILY -> "Gói Pro: đã dùng " + st.proUsedToday() + "/" + st.proDailyLimit() + " lượt miễn phí hôm nay";
            case WALLET -> "Đã dùng 1 lượt AI giải thích · Còn " + st.walletRemaining() + " lượt trong ví";
            case REVIEWER -> "";
        };
    }

    private static String normalizeSource(String raw) {
        if (raw == null || raw.isBlank()) return "JPATH_CHECK";
        String up = raw.trim().toUpperCase();
        return switch (up) {
            case "JPATH_CHECK", "JPATH_SKILL", "CPATH_ROLEPLAY", "CPATH_WEEKLY", "CPATH_CORE" -> up;
            default -> "JPATH_CHECK";
        };
    }

    private static String textOrEmpty(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
