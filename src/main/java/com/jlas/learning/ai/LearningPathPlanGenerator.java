package com.jlas.learning.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jlas.assessment.entity.SmartTestSkillResult;
import com.jlas.assessment.repository.SmartTestSkillResultRepository;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.ScheduledLearningResource;
import com.jlas.learning.dto.WeaknessEvidence;
import com.jlas.learning.enums.LearningPhase;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] Tầng cá nhân hoá lộ trình bằng Gemini — <b>tăng cường</b>, không thay thế engine
 * deterministic. Engine giữ nguyên việc chọn bài/xếp ngày/idempotency; lớp này chỉ viết narrative
 * cá nhân hoá (overview + chiến lược từng giai đoạn) cho UI, dựa trên kết quả Smart Test.
 *
 * <p>Best-effort: mọi lỗi (không key, rate-limit, JSON hỏng) trả {@code null} và UI vẫn chạy bình
 * thường không có narrative.</p>
 */
@Component
@RequiredArgsConstructor
public class LearningPathPlanGenerator {

    private static final String SYSTEM_PROMPT = """
            Bạn là trợ lý giáo viên tiếng Nhật của nền tảng JLAS. Nhiệm vụ: dựa trên kết quả bài \
            kiểm tra Smart Test (trình độ, điểm yếu từng kỹ năng) và lịch học đã được hệ thống xếp \
            sẵn, viết một bản kế hoạch học NGẮN GỌN, CÁ NHÂN HOÁ bằng tiếng Việt để động viên và \
            định hướng người học. KHÔNG được bịa nội dung bài học hay thay đổi lịch; chỉ diễn giải \
            và đưa lời khuyên chiến lược cho từng giai đoạn.\

            Trả về DUY NHẤT một object JSON đúng cấu trúc sau (không thêm chú thích ngoài JSON):
            {
              "overview": "<2-3 câu tóm tắt tình hình và hướng đi>",
              "phases": [
                {"phase": "WARM_UP", "strategy": "<câu>", "focus": "<câu>", "tips": "<câu>"},
                {"phase": "GOAL_LEARNING", "strategy": "<câu>", "focus": "<câu>", "tips": "<câu>"}
              ]
            }
            Các giá trị đều là chuỗi tiếng Việt, ngắn gọn (mỗi trường 1-2 câu). Nếu một giai đoạn \
            không có nội dung thì bỏ phần tử đó khỏi mảng phases.""";

    private final AiJsonGenerator aiJson;
    private final ObjectProvider<SmartTestSkillResultRepository> skillResults;

    /**
     * Sinh bản kế hoạch narrative; trả {@code null} nếu Gemini không dùng được.
     */
    public JsonNode generate(LearningProfileSnapshot profile, List<WeaknessEvidence> weaknesses,
            List<ScheduledLearningResource> schedule) {
        if (profile == null) {
            return null;
        }
        JsonNode node = aiJson.generateJson(SYSTEM_PROMPT, buildUserPrompt(profile, weaknesses, schedule));
        return normalize(node);
    }

    private String buildUserPrompt(LearningProfileSnapshot profile, List<WeaknessEvidence> weaknesses,
            List<ScheduledLearningResource> schedule) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hồ sơ người học:\n");
        sb.append("- Mục tiêu: ").append(profile.goalCode()).append('\n');
        sb.append("- Trình độ kết luận: ").append(profile.assessedLevel())
                .append(" → mục tiêu: ").append(profile.targetLevel()).append('\n');
        sb.append("- Thời lượng: ").append(profile.minutesPerDay()).append(" phút/ngày\n");

        appendSkillResults(sb, profile.assessmentAttemptId());
        appendWeaknesses(sb, weaknesses);
        appendSchedule(sb, schedule);
        return sb.toString();
    }

    private void appendSkillResults(StringBuilder sb, Long attemptId) {
        var repo = skillResults.getIfAvailable();
        if (repo == null || attemptId == null) {
            return;
        }
        try {
            List<SmartTestSkillResult> results = repo.findBySessionAttemptIdOrderByIdSkillAsc(attemptId);
            if (results.isEmpty()) {
                return;
            }
            sb.append("- Kết quả Smart Test theo kỹ năng:\n");
            for (var result : results) {
                sb.append("  • ").append(result.getId().getSkill())
                        .append(": trình độ ").append(result.getEstimatedLevel())
                        .append(", mastery ").append(result.getMasteryScore())
                        .append(", độ tin cậy ").append(result.getConfidence()).append('\n');
            }
        } catch (Exception ignored) {
            // best-effort: thiếu dữ liệu kỹ năng không chặn sinh narrative
        }
    }

    private void appendWeaknesses(StringBuilder sb, List<WeaknessEvidence> weaknesses) {
        if (weaknesses == null || weaknesses.isEmpty()) {
            sb.append("- Điểm yếu: không có bằng chứng đánh giá đáng kể.\n");
            return;
        }
        sb.append("- Điểm yếu đã kết luận từ Smart Test:\n");
        for (var weakness : weaknesses) {
            sb.append("  • ").append(weakness.skill())
                    .append(" [" ).append(weakness.canonicalId()).append("]")
                    .append(": sai ").append(weakness.wrongCount())
                    .append('/').append(weakness.assessedCount());
            if (weakness.jlptLevel() != null) {
                sb.append(", cấp ").append(weakness.jlptLevel());
            }
            sb.append('\n');
        }
    }

    private void appendSchedule(StringBuilder sb, List<ScheduledLearningResource> schedule) {
        sb.append("- Lịch học đã xếp (do hệ thống quyết định):\n");
        if (schedule == null || schedule.isEmpty()) {
            sb.append("  (không có)\n");
            return;
        }
        for (var item : schedule) {
            sb.append("  • Ngày ").append(item.dayNumber())
                    .append(" [").append(item.phase() == LearningPhase.WARM_UP ? "Khởi động" : "Mục tiêu").append("] ")
                    .append(item.resource().title())
                    .append(" (").append(item.reasonCode()).append(")\n");
        }
    }

    /**
     * Chuẩn hoá đầu ra: giữ nguyên object hợp lệ; trả {@code null} nếu Gemini trả shape sai để UI
     * fallback không có narrative thay vì hiển thị dữ liệu rác.
     */
    private JsonNode normalize(JsonNode node) {
        if (node == null || !node.isObject() || !node.hasNonNull("overview")) {
            return null;
        }
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode result = mapper.createObjectNode();
        result.put("overview", node.get("overview").asText("").trim());
        ArrayNode phases = mapper.createArrayNode();
        if (node.get("phases") != null && node.get("phases").isArray()) {
            for (JsonNode phase : node.get("phases")) {
                if (!phase.hasNonNull("phase")) {
                    continue;
                }
                ObjectNode copy = mapper.createObjectNode();
                copy.put("phase", phase.get("phase").asText());
                copy.put("strategy", phase.path("strategy").asText("").trim());
                copy.put("focus", phase.path("focus").asText("").trim());
                copy.put("tips", phase.path("tips").asText("").trim());
                phases.add(copy);
            }
        }
        result.set("phases", phases);
        return result;
    }
}
