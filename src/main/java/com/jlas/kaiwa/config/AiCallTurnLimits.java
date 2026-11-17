package com.jlas.kaiwa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Giới hạn độ dài một lượt AI Call (kế hoạch Pro/CP, mục 2), để lượt dài nhất vẫn dưới chi phí đã tính giá CP.
 * Câu trả lời của AI tối đa 900 token (M) nằm trong OpenRouterClient.
 *
 * @param messageMaxChars  tin nhắn của người học tối đa N ký tự (tin thật dài nhất đo được: 25 ký tự)
 * @param voiceMaxSeconds  một lần nói vào micro (trình duyệt nhận dạng) tự dừng sau chừng này giây
 * @param ttsMaxChars      giọng đọc tối đa K ký tự mỗi câu; câu dài hơn chỉ đọc tới dấu kết câu cuối cùng trong giới
 *                         hạn (câu AI dài nhất đo được: 40 ký tự)
 */
@ConfigurationProperties(prefix = "aicall.turn-limits")
public record AiCallTurnLimits(
        @DefaultValue("150") int messageMaxChars,
        @DefaultValue("30") int voiceMaxSeconds,
        @DefaultValue("200") int ttsMaxChars) {

    /** {@code text} cut to {@link #ttsMaxChars()}, at the last 。！？ inside the limit when there is one. */
    public String clipForVoice(String text) {
        if (text == null || text.codePointCount(0, text.length()) <= ttsMaxChars) {
            return text;
        }
        String head = text.substring(0, text.offsetByCodePoints(0, ttsMaxChars));
        int end = Math.max(Math.max(head.lastIndexOf('。'), head.lastIndexOf('！')),
                Math.max(head.lastIndexOf('？'), Math.max(head.lastIndexOf('!'), head.lastIndexOf('?'))));
        return end > 0 ? head.substring(0, end + 1) : head;
    }
}
