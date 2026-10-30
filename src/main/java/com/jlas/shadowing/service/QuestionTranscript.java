package com.jlas.shadowing.service;

import com.jlas.shadowing.model.Subtitle;
import com.jlas.shadowing.model.practice.QuestionEvidencePlan;
import java.text.Normalizer;
import java.util.*;

/** A derived AI view. Never edits the user's original subtitle text or timing. */
final class QuestionTranscript {
    static final String ANALYSIS_VERSION = "evidence-plans-v1";
    static final int MAX_EVIDENCE_LINES = 6;
    private QuestionTranscript() {}

    static String spoken(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("\\[[^\\]\\r\\n]{0,80}\\]|【[^】\\r\\n]{0,80}】", "")
                .replaceAll("\\s+", " ").strip();
    }

    static String sentenceKey(String text) {
        return spoken(text).replaceAll("[。.!！?？\\s]+$", "");
    }

    static List<List<Subtitle>> windows(List<Subtitle> all) {
        List<List<Subtitle>> windows = new ArrayList<>();
        // Eight shared lines keep split sentences and transitions visible in both analyses.
        for (int start = 0; start < all.size(); start += 40) {
            int end = Math.min(all.size(), start + 48);
            windows.add(List.copyOf(all.subList(start, end)));
            if (end == all.size()) break;
        }
        return windows;
    }

    static List<Map<String, Object>> aiView(List<Subtitle> subtitles) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Long> recent = new HashMap<>();
        for (Subtitle subtitle : subtitles) {
            String text = spoken(subtitle.getText());
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", subtitle.getId());
            line.put("text", text);
            line.put("originalText", subtitle.getText());
            line.put("startTime", subtitle.getStartTime());
            line.put("endTime", subtitle.getEndTime());
            line.put("fragment", !text.isEmpty() && !text.matches(".*[。.!！?？]$"));
            Long duplicate = recent.put(sentenceKey(text), subtitle.getId());
            if (duplicate != null && !text.isBlank()) line.put("repeatOf", duplicate);
            result.add(line);
        }
        return result;
    }

    static List<Map<String, Object>> diagnosticView(List<Subtitle> supplied, List<Long> markedIds) {
        List<Map<String, Object>> snapshot = aiView(supplied);
        snapshot.forEach(line -> line.put("marked", markedIds.contains((Long) line.get("id"))));
        return snapshot;
    }

    static List<Subtitle> evidence(QuestionEvidencePlan plan, List<Subtitle> all) {
        Map<Long, Subtitle> byId = new HashMap<>();
        all.forEach(s -> byId.put(s.getId(), s));
        return plan.sourceIds().stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    static List<Long> neighbors(List<Long> ids, List<Subtitle> all) {
        int first = -1, last = -1;
        for (int i = 0; i < all.size(); i++) {
            if (ids.contains(all.get(i).getId())) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0) return List.of();
        return all.subList(Math.max(0, first - 4), Math.min(all.size(), last + 5))
                .stream().map(Subtitle::getId).toList();
    }

    static boolean contiguous(List<Long> ids, Map<Long, Integer> positions) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_EVIDENCE_LINES
                || new HashSet<>(ids).size() != ids.size()) return false;
        Integer first = positions.get(ids.get(0));
        if (first == null) return false;
        for (int i = 0; i < ids.size(); i++) {
            if (!Integer.valueOf(first + i).equals(positions.get(ids.get(i)))) return false;
        }
        return true;
    }

    static boolean sameFact(QuestionEvidencePlan left, QuestionEvidencePlan right) {
        if (!left.kind().equals(right.kind())) return false;
        String a = factKey(left.answerQuote()), b = factKey(right.answerQuote());
        return left.sourceIds().stream().anyMatch(right.sourceIds()::contains)
                || a.equals(b) || (Math.min(a.length(), b.length()) >= 6
                && (a.contains(b) || b.contains(a)));
    }

    private static String factKey(String value) {
        return spoken(value).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\p{S}]+", "");
    }
}
