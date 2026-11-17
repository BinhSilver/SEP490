package com.jlas.tutor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Giới hạn của chatbot gia sư. Chatbot bật khi {@code kaiwa.rag.enabled=true}.
 *
 * @param maxConcurrent      số câu hỏi gia sư xử lý cùng lúc trên một máy chủ
 * @param waitSeconds        thời gian một câu hỏi chờ chỗ trống trước khi báo bận
 * @param questionsPerHour   số câu hỏi tối đa của một người trong 60 phút
 * @param maxQuestionChars   độ dài tối đa một câu hỏi
 * @param historyMessages    số lượt trước gửi kèm để model hiểu câu hỏi nối tiếp
 * @param aiFallback         tầng 2: J-LAS không có nội dung cho một câu hỏi tiếng Nhật thì AI tổng hợp câu trả lời có nhãn
 * @param aiFallbackPerDay   số lượt AI tổng hợp tối đa của một người trong 24 giờ; bản đã lưu không tính
 */
@ConfigurationProperties(prefix = "tutor")
public record TutorProperties(
        @DefaultValue("2") int maxConcurrent,
        @DefaultValue("30") int waitSeconds,
        @DefaultValue("60") int questionsPerHour,
        @DefaultValue("500") int maxQuestionChars,
        @DefaultValue("6") int historyMessages,
        @DefaultValue("false") boolean aiFallback,
        @DefaultValue("20") int aiFallbackPerDay) {

    public TutorProperties {
        aiFallbackPerDay = Math.max(0, aiFallbackPerDay);
        maxConcurrent = Math.max(1, maxConcurrent);
        waitSeconds = Math.max(0, waitSeconds);
        questionsPerHour = Math.max(1, questionsPerHour);
        maxQuestionChars = Math.clamp(maxQuestionChars, 20, 2_000);
        historyMessages = Math.clamp(historyMessages, 0, 20);
    }
}
