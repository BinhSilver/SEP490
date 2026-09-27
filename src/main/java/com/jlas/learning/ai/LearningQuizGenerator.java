package com.jlas.learning.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.jlas.learning.entity.LearningGeneratedQuestion;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.repository.LearningGeneratedQuestionRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Sinh câu quiz Gemini để <b>bù</b> phần ngân hàng câu thiếu cho gate quiz — theo chiến
 * lược "ngân hàng trước, Gemini bù". Câu sinh ra lưu vào bảng riêng
 * {@link LearningGeneratedQuestion}, không đụng ngân hàng Smart Test.
 *
 * <p>Idempotent: mỗi anchor chỉ sinh đúng một câu cho một path (unique key path+anchor). Best-effort:
 * anchor không sinh được thì bỏ qua, quiz vẫn chạy với các câu còn lại.</p>
 */
@Component
@RequiredArgsConstructor
public class LearningQuizGenerator {

    private static final String SYSTEM_PROMPT = """
            Bạn là giáo viên tiếng Nhật của nền tảng JLAS. Nhiệm vụ: soạn MỘT câu hỏi trắc nghiệm \
            4 lựa chọn để kiểm tra kiến thức người học đã ôn, BÁM SÁT đúng nội dung được cung cấp \
            (không bịa nội dung ngoài anchor). Câu hỏi phù hợp trình độ JLPT đã ghi.\

            Trả về DUY NHẤT một object JSON đúng cấu trúc (không thêm chú thích ngoài JSON):
            {
              "questionText": "<câu hỏi tiếng Nhật hoặc tiếng Việt, rõ ràng>",
              "options": ["<A>", "<B>", "<C>", "<D>"],
              "correctOptionIndex": <0..3>,
              "explanation": "<giải thích ngắn gọn tại sao đáp án đúng>"
            }
            Đủ đúng 4 lựa chọn, đúng 1 đáp án, index từ 0 đến 3.""";

    private final AiJsonGenerator aiJson;
    private final LearningGeneratedQuestionRepository generated;

    /** Anchor nội dung đã ôn cần sinh câu hỏi; {@code detail} là mô tả để ground prompt. */
    public record Anchor(String type, String id, String skill, String jlptLevel, String detail) { }

    /**
     * Sinh và lưu câu hỏi cho các anchor còn thiếu; trả về danh sách câu đã lưu (có thể ngắn hơn
     * input nếu Gemini lỗi hoặc JSON hỏng). Không ném ngoại lệ.
     */
    @Transactional
    public List<LearningGeneratedQuestion> generate(UserLearningPath path, List<Anchor> anchors) {
        if (path == null || path.getId() == null || anchors == null || anchors.isEmpty()) {
            return List.of();
        }
        List<LearningGeneratedQuestion> created = new ArrayList<>();
        for (Anchor anchor : anchors) {
            if (anchor == null) {
                continue;
            }
            if (generated.existsByLearningPathIdAndAnchorTypeAndAnchorId(
                    path.getId(), anchor.type(), anchor.id())) {
                continue; // đã sinh trước đó — idempotent
            }
            JsonNode node = aiJson.generateJson(SYSTEM_PROMPT, anchorPrompt(anchor));
            LearningGeneratedQuestion question = toEntity(path, anchor, node);
            if (question == null) {
                continue;
            }
            created.add(generated.save(question));
        }
        return created;
    }

    private String anchorPrompt(Anchor anchor) {
        StringBuilder sb = new StringBuilder();
        sb.append("Trình độ: ").append(anchor.jlptLevel() == null ? "N5" : anchor.jlptLevel()).append('\n');
        sb.append("Kỹ năng: ").append(anchor.skill() == null ? "GRAMMAR" : anchor.skill()).append('\n');
        sb.append("Nội dung đã ôn (anchor):\n").append(anchor.detail() == null ? anchor.id() : anchor.detail());
        sb.append("\nSoạn một câu hỏi kiểm tra đúng nội dung trên.");
        return sb.toString();
    }

    /** Chuyển JSON Gemini thành entity; {@code null} nếu JSON thiếu/không hợp lệ (best-effort). */
    private LearningGeneratedQuestion toEntity(UserLearningPath path, Anchor anchor, JsonNode node) {
        if (node == null || !node.isObject() || !node.hasNonNull("questionText")) {
            return null;
        }
        String questionText = node.get("questionText").asText("").trim();
        if (questionText.isEmpty()) {
            return null;
        }
        List<String> options = extractOptions(node);
        if (options.size() != 4 || options.stream().anyMatch(String::isBlank)) {
            return null;
        }
        int correctOption = extractCorrectOption(node);
        if (correctOption < 1 || correctOption > 4) {
            return null;
        }
        String explanation = node.hasNonNull("explanation") ? node.get("explanation").asText("").trim() : null;
        return LearningGeneratedQuestion.builder()
                .learningPath(path)
                .anchorType(anchor.type())
                .anchorId(anchor.id())
                .skill(anchor.skill() == null ? "GRAMMAR" : anchor.skill())
                .jlptLevel(anchor.jlptLevel())
                .questionText(questionText)
                .option1(options.get(0))
                .option2(options.get(1))
                .option3(options.get(2))
                .option4(options.get(3))
                .correctOption(correctOption)
                .explanation(explanation == null || explanation.isBlank() ? null : explanation)
                .status("ACTIVE")
                .build();
    }

    private List<String> extractOptions(JsonNode node) {
        if (node.hasNonNull("options") && node.get("options").isArray()) {
            List<String> options = new ArrayList<>();
            for (JsonNode option : node.get("options")) {
                options.add(option.asText("").trim());
            }
            if (options.size() == 4) {
                return options;
            }
        }
        // Fallback: option_1..option_4.
        return List.of(node.path("option_1").asText("").trim(),
                node.path("option_2").asText("").trim(),
                node.path("option_3").asText("").trim(),
                node.path("option_4").asText("").trim());
    }

    private int extractCorrectOption(JsonNode node) {
        if (node.hasNonNull("correctOptionIndex") && node.get("correctOptionIndex").isInt()) {
            return node.get("correctOptionIndex").asInt() + 1;
        }
        if (node.hasNonNull("correctOption") && node.get("correctOption").isInt()) {
            return node.get("correctOption").asInt();
        }
        return 0;
    }
}
