package com.jlas.assessment.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

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

}
