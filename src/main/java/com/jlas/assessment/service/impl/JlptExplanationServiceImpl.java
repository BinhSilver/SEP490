package com.jlas.assessment.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.assessment.dto.JlptExplanationRequest;
import com.jlas.assessment.dto.JlptExplanationResponse;
import com.jlas.assessment.service.JlptExplanationService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class JlptExplanationServiceImpl implements JlptExplanationService {

    private static final String SYSTEM_PROMPT = """
            Bạn là giáo viên JLPT N3 đang chữa bài trực tiếp cho học viên Việt Nam.
            Hãy giải thích chính xác, tận tình, dễ ôn lại và chỉ dựa trên dữ liệu được cung cấp.
            Không bịa cách đọc, nghĩa, bằng chứng hoặc nội dung audio. Nếu dữ liệu chưa đủ, phải nói rõ.

            Trước khi trả lời, tự xác định dạng câu và dạng đáp án nhiễu:
            - Gần âm/gần chữ: so sánh mora, trường âm, âm đục, âm ngắt, âm ghép hoặc bộ Kanji.
            - Gần nghĩa: phân biệt sắc thái, chủ thể, đối tượng, mức độ, văn phong và collocation.
            - Khác nghĩa: nêu nghĩa ngắn và cách dùng điển hình của từng lựa chọn, tập trung từ khóa quyết định.
            - Ngữ pháp: nêu cấu trúc, cách chia, nghĩa bản chất và quan hệ logic trước/sau.
            - Đọc/nghe: trích đúng evidence có trong passage/transcript và chỉ ra bẫy của đáp án sai.
            - Khi nhiều từ tạo thành cụm từ, collocation hoặc cấu trúc cố định, hãy giải thích nghĩa của cả cụm
              và vai trò trong câu, không chỉ dịch rời từng từ.
            - Không phiên âm Romaji, không ghi cách đọc tiếng Nhật bằng hiragana trong ngoặc.
            - Nếu từ/cụm từ cần giải thích có Kanji và dữ liệu cung cấp âm Hán Việt tra từ điển nội bộ,
              hãy ghi âm Hán Việt theo từng chữ ngay trong mục Từ vựng / Ngữ pháp, ví dụ:
              "勉強 (勉: MIỄN, 強: CƯỜNG)". Chỉ dùng âm được cung cấp; không tự suy đoán.

            Phản hồi bằng tiếng Việt với ĐÚNG 4 mục, đúng thứ tự, không thêm lời chào hoặc mục khác:
            Đáp án đúng: chỉ ghi số đáp án và nội dung đáp án đúng; không nhận xét học viên đã chọn hay chưa chọn.
            Từ vựng / Ngữ pháp: giải thích trực tiếp nghĩa, cấu trúc, cách dùng hoặc sắc thái; ghi âm Hán Việt
            cho Kanji liên quan nếu có dữ liệu; không ghi cách đọc tiếng Nhật hay Romaji.
            Dịch câu: bản dịch tự nhiên của câu hoặc phần passage/transcript quyết định đáp án.
            Tham khảo: phân tích đủ từng lựa chọn, bắt buộc mỗi lựa chọn ở một dòng riêng theo đúng dạng
            "1. ...", "2. ...", "3. ...", "4. ...". Mẹo ghi nhớ/giải nhanh phải ở dòng riêng bắt đầu bằng "Mẹo:".

            Tối đa 380 từ. Không dùng Markdown table. Không nhắc đến AI.
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Map<String, JlptExplanationResponse> cache = new ConcurrentHashMap<>();

    @Value("${openrouter.api.key:}")
    private String apiKey;

    @Value("${openrouter.api.url:https://openrouter.ai/api/v1}")
    private String apiBaseUrl;

    @Value("${openrouter.site.url:http://localhost:8080}")
    private String siteUrl;

    @Value("${openrouter.model:openai/gpt-4o-mini}")
    private String model;

    public JlptExplanationServiceImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public JlptExplanationResponse explain(JlptExplanationRequest request) {
        String key = request.questionId() + ":" + request.selectedChoice() + ":han-viet-v1";
        return cache.computeIfAbsent(key, ignored -> createExplanation(request));
    }

    private JlptExplanationResponse createExplanation(JlptExplanationRequest request) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT q.id,q.question_number,q.question_text,q.correct_option,
                       m.id AS mondai_id,COALESCE(qt.type_code,m.format_code) AS format_code,p.part_code,
                       ps.passage_ja
                FROM jlpt_question q
                JOIN jlpt_mondai m ON m.id=q.mondai_id
                JOIN jlpt_part p ON p.id=m.part_id
                LEFT JOIN jlpt_passage ps ON ps.id=q.passage_id
                LEFT JOIN jlpt_question_type qt ON qt.id=q.question_type_id
                WHERE q.id=?
                """, request.questionId());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy câu hỏi JLPT.");
        }
        Map<String, Object> question = rows.getFirst();
        Integer correctChoice = nullableInt(question.get("correct_option"));
        if (correctChoice == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Câu này chưa có đáp án chuẩn.");
        }
        List<Map<String, Object>> options = jdbc.queryForList("""
                SELECT option_number,option_text FROM jlpt_option
                WHERE question_id=? ORDER BY order_index
                """, request.questionId());
        List<Map<String, Object>> transcript = jdbc.queryForList("""
                SELECT t.text_ja,t.text_vi FROM jlpt_transcript_segment t
                LEFT JOIN jlpt_audio_clip c ON c.id=t.audio_clip_id
                WHERE t.mondai_id=? AND (t.audio_clip_id IS NULL OR c.question_id=?)
                ORDER BY t.order_index
                """, question.get("mondai_id"), request.questionId());
        boolean correct = request.selectedChoice() != null && request.selectedChoice().equals(correctChoice);
        if (apiKey == null || apiKey.isBlank()) {
            return new JlptExplanationResponse(
                    fallback(options, request.selectedChoice(), correctChoice), correctChoice, correct, false);
        }
        try {
            String explanation = callAi(questionContext(question, options, transcript,
                    request.selectedChoice(), correctChoice));
            if (!explanation.isBlank()) {
                return new JlptExplanationResponse(explanation, correctChoice, correct, true);
            }
        } catch (Exception ignored) {
            // Keep practice usable when the configured AI provider is temporarily unavailable.
        }
        return new JlptExplanationResponse(
                fallback(options, request.selectedChoice(), correctChoice), correctChoice, correct, false);
    }

    private String questionContext(
            Map<String, Object> question,
            List<Map<String, Object>> options,
            List<Map<String, Object>> transcript,
            Integer selectedChoice,
            int correctChoice) {
        StringBuilder context = new StringBuilder()
                .append("Phần thi: ").append(text(question.get("part_code")))
                .append("\nDạng câu: ").append(text(question.get("format_code")))
                .append("\nCâu hỏi: ").append(text(question.get("question_text")))
                .append("\nCác lựa chọn:");
        options.forEach(option -> context.append("\n")
                .append(number(option.get("option_number")).intValue()).append(". ")
                .append(text(option.get("option_text"))));
        context.append("\nHọc viên chọn: ").append(selectedChoice == null ? "chưa chọn" : selectedChoice)
                .append("\nĐáp án chuẩn: ").append(correctChoice);
        String passage = text(question.get("passage_ja"));
        if (!passage.isBlank()) context.append("\nPassage:\n").append(limit(passage, 9000));
        if (!transcript.isEmpty()) {
            context.append("\nTranscript:");
            for (Map<String, Object> segment : transcript) {
                context.append("\nJA: ").append(text(segment.get("text_ja")));
                String vi = text(segment.get("text_vi"));
                if (!vi.isBlank()) context.append("\nVI: ").append(vi);
                if (context.length() > 14000) break;
            }
        }
        String hanViet = hanVietReference(question, options, passage, transcript);
        if (!hanViet.isBlank()) {
            context.append("\nÂm Hán Việt tra từ điển nội bộ (Kanji: âm Hán Việt): ").append(hanViet);
        }
        return context.toString();
    }

    private String hanVietReference(
            Map<String, Object> question,
            List<Map<String, Object>> options,
            String passage,
            List<Map<String, Object>> transcript) {
        StringBuilder source = new StringBuilder(text(question.get("question_text"))).append(' ').append(passage);
        options.forEach(option -> source.append(' ').append(text(option.get("option_text"))));
        transcript.forEach(segment -> source.append(' ').append(text(segment.get("text_ja"))));

        Set<String> characters = new LinkedHashSet<>();
        source.toString().codePoints()
                .filter(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)
                .distinct()
                .limit(80)
                .mapToObj(codePoint -> new String(Character.toChars(codePoint)))
                .forEach(characters::add);
        if (characters.isEmpty()) return "";

        List<String> parameters = new ArrayList<>(characters);
        String placeholders = String.join(",", java.util.Collections.nCopies(parameters.size(), "?"));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT `character` AS kanji_character,han_viet FROM kanji "
                        + "WHERE `character` IN (" + placeholders + ") "
                        + "AND han_viet IS NOT NULL AND TRIM(han_viet)<>''",
                parameters.toArray());
        return rows.stream()
                .map(row -> text(row.get("kanji_character")) + ": " + text(row.get("han_viet")))
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }

    private String callAi(String context) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("temperature", 0.2);
        payload.put("max_tokens", 900);
        payload.put("messages", new Object[]{
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", context)
        });
        String endpoint = apiBaseUrl == null || apiBaseUrl.isBlank()
                ? "https://openrouter.ai/api/v1/chat/completions"
                : apiBaseUrl.replaceAll("/+$", "") + "/chat/completions";
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(35))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("HTTP-Referer", siteUrl)
                .header("X-Title", "JLAS JLPT Practice")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) return "";
        return objectMapper.readTree(response.body()).path("choices").path(0)
                .path("message").path("content").asText("").trim();
    }

    private String fallback(List<Map<String, Object>> options, Integer selectedChoice, int correctChoice) {
        String correctText = options.stream()
                .filter(option -> number(option.get("option_number")).intValue() == correctChoice)
                .map(option -> text(option.get("option_text"))).findFirst().orElse("");
        return "Đáp án đúng: " + correctChoice + " - " + correctText + "."
                + "\nTừ vựng / Ngữ pháp: Chưa có giải thích chi tiết vì dịch vụ AI chưa được cấu hình hoặc đang gián đoạn."
                + "\nDịch câu: —"
                + "\nTham khảo: Hãy đối chiếu từ khóa của câu với nghĩa và cách dùng của từng lựa chọn.";
    }

    private static Number number(Object value) {
        return (Number) value;
    }

    private static Integer nullableInt(Object value) {
        return value == null ? null : number(value).intValue();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String limit(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length) + "…";
    }
}
