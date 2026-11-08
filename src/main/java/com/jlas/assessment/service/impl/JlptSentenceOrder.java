package com.jlas.assessment.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.assessment.dto.JlptAdminQuestionRequest;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class JlptSentenceOrder {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern BLANK = Pattern.compile("[_＿―—]+[★☆](?:[ \\t]*[_＿―—]+)?|[★☆][_＿―—]*|[_＿―—]{2,}");

    private JlptSentenceOrder() {}

    static List<Integer> read(Object value) {
        if (value == null) return List.of();
        try {
            Integer[] order = MAPPER.readValue(value.toString(), Integer[].class);
            if (order == null) return List.of();
            List<Integer> result = java.util.Arrays.asList(order);
            if (result.size() != 4 || result.contains(null) || new HashSet<>(result).size() != 4
                    || result.stream().anyMatch(n -> n < 1 || n > 4)) return List.of();
            return List.copyOf(result);
        } catch (Exception e) { return List.of(); }
    }

    static int starPosition(String prompt) {
        String plain = prompt.replaceAll("(?is)<rt\\b[^>]*>.*?</rt>|<rp\\b[^>]*>.*?</rp>", "")
                .replaceAll("<[^>]+>", "");
        var matcher = BLANK.matcher(plain);
        int count = 0, star = -1;
        while (matcher.find()) {
            if (matcher.group().contains("★") || matcher.group().contains("☆")) {
                if (star >= 0) return -1;
                star = count;
            }
            count++;
        }
        return count == 4 ? star : -1;
    }

    static Integer correctChoice(JlptAdminQuestionRequest request) {
        List<Integer> order = request.correctOrder();
        if (order == null) return request.correctChoice();
        if (!"SENTENCE_COMPOSITION".equals(request.type()) || request.choices().size() != 4
                || order.size() != 4 || order.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(order).size() != 4
                || order.stream().anyMatch(n -> n < 1 || n > 4)) throw invalid("Thứ tự đúng phải gồm đủ 1, 2, 3, 4, mỗi số một lần, cho câu sắp xếp.");
        int star = starPosition(request.prompt());
        if (star < 0) throw invalid("Câu sắp xếp phải có bốn chỗ trống và một vị trí ★.");
        Integer correct = order.get(star);
        if (request.correctChoice() != null && !correct.equals(request.correctChoice())) throw invalid("Đáp án ★ không khớp với thứ tự đúng.");
        return correct;
    }

    static String write(JlptAdminQuestionRequest request) {
        correctChoice(request);
        return request.correctOrder() == null ? null : request.correctOrder().toString();
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
