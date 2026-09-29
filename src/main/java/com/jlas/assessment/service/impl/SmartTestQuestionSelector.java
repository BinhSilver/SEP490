package com.jlas.assessment.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.assessment.entity.AssessmentQuestion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Randomize within each goal-priority tier, excluding issued IDs and repeated Kanji words. */
public final class SmartTestQuestionSelector {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern EXPLANATION_WORD = Pattern.compile(
            "^([\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー々]+)\\s*(?:[（(]|đọc)");

    static List<AssessmentQuestion> select(List<AssessmentQuestion> pool,
                                           List<AssessmentQuestion> issued, int size, List<String> preferredTags) {
        return select(pool, issued, size, preferredTags, new Random());
    }

    static List<AssessmentQuestion> select(List<AssessmentQuestion> pool,
                                           List<AssessmentQuestion> issued, int size,
                                           List<String> preferredTags, Random random) {
        if (size <= 0) return List.of();
        Set<Long> usedIds = new HashSet<>();
        Set<String> usedWords = new HashSet<>();
        for (AssessmentQuestion question : issued) {
            usedIds.add(question.getId());
            if ("KANJI".equals(question.getSkill())) usedWords.add(kanjiWord(question));
        }
        List<AssessmentQuestion> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, random);
        // Stable sort retains the random order within each goal-priority tier.
        // A question matches the goal when its primary tag OR one of its additional_tags is preferred.
        shuffled.sort(Comparator.comparingInt(question -> {
            int rank = preferredTags.size();
            if (question.getKnowledgeTag() != null && question.getKnowledgeTag().getCode() != null) {
                rank = Math.min(rank, tagRank(question.getKnowledgeTag().getCode(), preferredTags));
            }
            for (String additional : additionalTags(question)) {
                rank = Math.min(rank, tagRank(additional, preferredTags));
            }
            return rank;
        }));
        List<AssessmentQuestion> selected = new ArrayList<>();
        for (AssessmentQuestion question : shuffled) {
            if (!usedIds.add(question.getId())) continue;
            if ("KANJI".equals(question.getSkill()) && !usedWords.add(kanjiWord(question))) continue;
            selected.add(question);
            if (selected.size() == size) break;
        }
        return selected;
    }

    /** [JLAS-W0108] Trích lexeme/word chuẩn hóa từ context_json để làm canonical item KANJI/VOCAB. */
    public static String lexeme(AssessmentQuestion question) {
        try {
            JsonNode context = JSON.readTree(question.getContextJson());
            if (context != null) {
                for (String key : List.of("lexeme", "word")) {
                    String value = context.path(key).asText("").trim();
                    if (!value.isEmpty()) return value;
                }
            }
        } catch (Exception ignored) {
            // Historical seed rows may have no context; canonical item is then underivable.
        }
        return null;
    }

    static String kanjiWord(AssessmentQuestion question) {
        try {
            JsonNode context = JSON.readTree(question.getContextJson());
            if (context != null) {
                for (String key : List.of("lexeme", "word")) {
                    String value = context.path(key).asText("").trim();
                    if (!value.isEmpty()) return value;
                }
            }
        } catch (Exception ignored) {
            // Historical seed rows may have no context; fall back to their explanation.
        }
        Matcher matcher = EXPLANATION_WORD.matcher(
                question.getExplanation() == null ? "" : question.getExplanation());
        return matcher.find() ? matcher.group(1) : "question:" + question.getId();
    }

    private static int tagRank(String tag, List<String> preferredTags) {
        int index = preferredTags.indexOf(tag);
        return index < 0 ? preferredTags.size() : index;
    }

    /** [JLAS-W0108] Câu khớp mục tiêu khi tag chính HOẶC một additional tag nằm trong preferred tags. */
    static boolean matchesGoal(AssessmentQuestion question, List<String> preferredTags) {
        if (preferredTags == null || preferredTags.isEmpty()) return true;
        if (question.getKnowledgeTag() != null && question.getKnowledgeTag().getCode() != null
                && preferredTags.contains(question.getKnowledgeTag().getCode())) return true;
        return additionalTags(question).stream().anyMatch(preferredTags::contains);
    }

    /** Các tag tình huống phụ (context_json.additional_tags) dùng để khớp mục tiêu. */
    static List<String> additionalTags(AssessmentQuestion question) {
        try {
            JsonNode context = JSON.readTree(question.getContextJson());
            JsonNode additional = context == null ? null : context.path("additional_tags");
            List<String> tags = new ArrayList<>();
            if (additional != null && additional.isArray()) {
                for (JsonNode node : additional) {
                    if (node.isTextual() && !node.asText().isBlank()) tags.add(node.asText());
                }
            }
            return tags;
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
