package com.jlas.learning.dto;

/**
 * [JLAS-W0108] Điểm số gộp theo kỹ năng từ TOÀN BỘ câu trả lời Smart Test (không phải danh sách
 * weakness đã lọc bỏ câu đúng). Dùng để xét pass cấp hiện tại (>60% đúng tổng thể) và phân tầng
 * ôn tập từng kỹ năng: &lt;40% ôn toàn bộ, 40–60% ôn đúng item sai, &gt;60% bỏ qua.
 *
 * @param assessedCount số câu hợp lệ đã đánh giá cho kỹ năng này
 * @param wrongCount    số câu sai (gồm NO_RESPONSE do hết giờ)
 */
public record SkillScore(String skill, int assessedCount, int wrongCount) {

    public int correctCount() {
        return assessedCount - wrongCount;
    }

    /** Tỉ lệ đúng trong [0, 1]; trả 0 nếu không có câu nào (tránh chia cho 0). */
    public double correctRatio() {
        return assessedCount <= 0 ? 0.0 : (double) correctCount() / assessedCount;
    }
}
