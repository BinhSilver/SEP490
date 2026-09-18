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
final class SmartTestQuestionSelector {
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
        shuffled.sort(Comparator.comparingInt(question -> {
            String tag = question.getKnowledgeTag() == null ? null : question.getKnowledgeTag().getCode();
            int rank = preferredTags.indexOf(tag);
            return rank < 0 ? preferredTags.size() : rank;
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
}
