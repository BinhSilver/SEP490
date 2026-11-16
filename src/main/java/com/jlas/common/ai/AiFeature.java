package com.jlas.common.ai;

import java.util.Locale;

/**
 * Mỗi tính năng gọi AI có một tên riêng (kế hoạch Pro/CP, mục 2): tên này được ghi vào ai_usage_log cùng chi phí, để
 * trang admin "Chi phí AI" biết tính năng nào dùng nhiều hay ít, và là tên API key OpenRouter riêng của tính năng.
 * <p>
 * Dashboard OpenRouter chỉ tách chi phí theo app (một tên miền) và theo API key, nên muốn xem từng tính năng ngay trên
 * OpenRouter thì tạo một key cho mỗi tính năng, đặt tên key giống {@link #name()}, rồi cấu hình
 * {@code openrouter.feature-keys.<configKey>} (env {@code OPENROUTER_FEATURE_KEYS_<NAME>}). Chưa có key riêng thì dùng
 * key chung như trước.
 */
public enum AiFeature {
    AI_CALL_REPLY("AI Call: câu trả lời"),
    AI_CALL_SUGGEST("AI Call: gợi ý câu trả lời"),
    AI_CALL_FURIGANA("AI Call: furigana"),
    AI_CALL_TRANSLATE("AI Call: dịch"),
    AI_CALL_MEMORY_CHAT("AI Call: trò chuyện có trí nhớ"),
    AI_CALL_MEMORY_EXTRACT("AI Call: ghi nhớ thông tin người học"),
    AI_CALL_MEMORY_EMBED("AI Call: tìm lại trí nhớ"),
    AI_CALL_TTS("AI Call: giọng đọc"),
    PRONUNCIATION_CHECK("Chấm phát âm"),
    SHADOWING_SUBTITLES("Shadowing: furigana và dịch phụ đề"),
    SHADOWING_QUESTIONS("Shadowing: câu hỏi theo video"),
    SHADOWING_EXERCISES("Shadowing: bài tập theo video"),
    JLPT_EXPLAIN("Đề JLPT: giải thích đáp án"),
    LEARNING_AI_EXPLAIN("Lộ trình: AI giải thích câu hỏi"),
    FLASHCARD_HAN_VIET("Flashcard: âm Hán Việt"),
    FLASHCARD_AI_LOOKUP("Flashcard: tra từ bằng AI"),
    MODERATION_IMAGE("Kiểm duyệt ảnh");

    private final String label;

    AiFeature(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** {@code ai-call-reply}: the key under {@code openrouter.feature-keys}. */
    public String configKey() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** This feature's own OpenRouter key when one is configured, otherwise {@code sharedKey}. */
    public String apiKey(String sharedKey) {
        String own = AiFeatureKeys.lookup(this);
        String key = own != null && !own.isBlank() ? own : sharedKey;
        return key == null ? "" : key.trim();
    }

    /** The label of a logged feature name, or the name itself for one that is no longer in this list. */
    public static String labelOf(String name) {
        for (AiFeature feature : values()) {
            if (feature.name().equals(name)) {
                return feature.label;
            }
        }
        return name;
    }
}
