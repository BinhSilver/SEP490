package com.jlas.learning.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.common.learning.CompiledBookPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * §2.32 (owner 2026-10-26) — at most {@value #LIMIT} extra Reading/Listening passages for one learner in one chapter. Pure:
 * the same rows and signals always give the same list. Candidates are the level's store passages (chapter copies and pool)
 * except HOLD and the excluded groups (the activity's own, the learner's unfinished required content, done in 14 days).
 * Rank: (1) most refs shared with the chapter's latest wrong answers, (2) difficulty nearest the target, (3) chapter hint
 * nearest the chapter, then source order (easy → hard, then code).
 */
final class ExtraPracticeSelection {
    static final int LIMIT = 3;
    /** F1-5 (owner 2026-10-28): a skill the learner chose to focus on is offered more passages. */
    static final int FOCUS_LIMIT = 5;
    /** Shown on a DRAFT or J-LAS supplement passage wherever it is practised. */
    static final String SUPPLEMENT_LABEL = "Nội dung bổ sung J-LAS · cần rà soát";
    private static final ObjectMapper JSON = new ObjectMapper();

    record Suggestion(String group, List<Long> questionIds, int chapterHint, int difficulty, int minutes, String review,
            String label, List<String> reasons) { }

    private record Passage(String group, List<Long> ids, JsonNode labels) {
        int difficulty() { return labels.path("difficulty").asInt(3); }
        int chapterHint() { return labels.path("chapter_hint").asInt(0); }
    }

    private ExtraPracticeSelection() {
    }

    /**
     * @param storeRows       active rows of the level and skill coded {@code LP-OWNED-JLAS-{L}-…}, ids ascending
     * @param excludedGroups  passage groups never to suggest
     * @param wrongQuestionIds questions the learner got wrong in the chapter's latest checks (weakness signal)
     * @param targetDifficulty 1–5
     */
    static List<Suggestion> select(List<AssessmentQuestion> storeRows, String skill, String level, int chapter,
            Set<String> excludedGroups, Set<Long> wrongQuestionIds, int targetDifficulty) {
        return select(storeRows, skill, level, chapter, excludedGroups, wrongQuestionIds, targetDifficulty, LIMIT);
    }

    static List<Suggestion> select(List<AssessmentQuestion> storeRows, String skill, String level, int chapter,
            Set<String> excludedGroups, Set<Long> wrongQuestionIds, int targetDifficulty, int limit) {
        List<Passage> passages = passages(storeRows, skill, level);
        Set<String> weakRefs = new HashSet<>();
        for (Passage passage : passages) {
            if (passage.ids().stream().anyMatch(wrongQuestionIds::contains)) refs(passage.labels(), weakRefs);
        }
        return passages.stream()
                .filter(p -> !excludedGroups.contains(p.group()))
                .filter(p -> !"HOLD".equals(p.labels().path("review").asText("")))
                .sorted(Comparator.comparingInt((Passage p) -> -shared(p.labels(), weakRefs))
                        .thenComparingInt(p -> Math.abs(p.difficulty() - targetDifficulty))
                        .thenComparingInt(p -> Math.abs(p.chapterHint() - chapter))
                        .thenComparingInt(Passage::difficulty)
                        .thenComparing(Passage::group))
                .limit(limit)
                .map(p -> suggestion(p, weakRefs, targetDifficulty, chapter))
                .toList();
    }

    /** §2.32 step 2: ≥ 90% one level harder than the chapter's own passages, < 60% one easier; no score at all → 3. */
    static int targetDifficulty(Integer referencePercent, double chapterDifficulty) {
        if (referencePercent == null) return 3;
        int base = (int) Math.round(chapterDifficulty);
        int shift = referencePercent >= 90 ? 1 : referencePercent < 60 ? -1 : 0;
        return Math.max(1, Math.min(5, base + shift));
    }

    /** Average labelled difficulty of the given rows' passages; 3 when none carries a label. */
    static double averageDifficulty(List<AssessmentQuestion> rows, String skill, String level) {
        return passages(rows, skill, level).stream().filter(p -> !p.labels().isMissingNode())
                .mapToInt(Passage::difficulty).average().orElse(3);
    }

    /** Passage group of a store row, or null. */
    static String group(AssessmentQuestion row, String skill, String level) {
        return CompiledBookPolicy.questionGroup(row.getSourceCode(), skill, level);
    }

    private static Suggestion suggestion(Passage p, Set<String> weakRefs, int target, int chapter) {
        List<String> reasons = new ArrayList<>();
        if (shared(p.labels(), weakRefs) > 0) reasons.add("WEAKNESS");
        if (p.difficulty() == target) reasons.add("DIFFICULTY");
        if (p.chapterHint() == chapter) reasons.add("SAME_CHAPTER");
        String review = p.labels().path("review").asText("ACCEPTED");
        boolean supplement = "DRAFT".equals(review) || "JLAS_SUPPLEMENT".equals(p.labels().path("origin").asText(""));
        return new Suggestion(p.group(), p.ids(), p.chapterHint(), p.difficulty(), p.labels().path("minutes").asInt(0),
                review, supplement ? SUPPLEMENT_LABEL : null, List.copyOf(reasons));
    }

    private static List<Passage> passages(List<AssessmentQuestion> rows, String skill, String level) {
        Map<String, List<AssessmentQuestion>> groups = new LinkedHashMap<>();
        for (AssessmentQuestion row : rows) {
            String group = group(row, skill, level);
            if (group != null) groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(row);
        }
        List<Passage> out = new ArrayList<>();
        groups.forEach((group, list) -> out.add(new Passage(group,
                list.stream().map(AssessmentQuestion::getId).toList(), labels(list.get(0)))));
        return out;
    }

    private static JsonNode labels(AssessmentQuestion row) {
        String text = row.getContextJson();
        if (text == null || text.isBlank()) return JSON.missingNode();
        try {
            return JSON.readTree(text).path("labels");
        } catch (Exception unreadable) {
            return JSON.missingNode();
        }
    }

    private static void refs(JsonNode labels, Set<String> into) {
        labels.path("grammar_refs").forEach(node -> into.add(node.asText()));
        labels.path("vocab_refs").forEach(node -> into.add(node.asText()));
    }

    private static int shared(JsonNode labels, Set<String> weakRefs) {
        if (weakRefs.isEmpty()) return 0;
        Set<String> mine = new HashSet<>();
        refs(labels, mine);
        mine.retainAll(weakRefs);
        return mine.size();
    }
}
