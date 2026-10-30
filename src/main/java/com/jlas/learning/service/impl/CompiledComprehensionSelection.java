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
 * §2.32 (owner 2026-10-26) — the required Reading/Listening of a compiled chapter. Its own passages stay, in their order, when
 * their store labels allow a required part ({@code ACCEPTED}/{@code REVIEWED}, never a pool passage); a passage that does
 * not is replaced, one for one, by store passages of the same chapter hint from the pool, best match first (most shared
 * grammar/vocabulary refs with the chapter, then nearest difficulty, then code). Copies seeded before the labels (no
 * {@code labels}) count as accepted, so a chapter with nothing to drop selects exactly what it always did.
 */
final class CompiledComprehensionSelection {
    private static final ObjectMapper JSON = new ObjectMapper();

    record Result(List<Long> ids, String gapReason) { }

    private record Passage(String group, List<AssessmentQuestion> rows, JsonNode labels) { }

    private CompiledComprehensionSelection() {
    }

    static Result select(List<AssessmentQuestion> chapterRows, java.util.function.Supplier<List<AssessmentQuestion>> poolRows,
            int chapter, String skill, String level) {
        List<Passage> own = passages(chapterRows, skill, level);
        List<Passage> kept = own.stream().filter(CompiledComprehensionSelection::required).toList();
        int missing = own.size() - kept.size();
        List<Long> ids = new ArrayList<>(kept.stream().flatMap(p -> p.rows().stream()).map(AssessmentQuestion::getId).toList());
        if (missing > 0) {
            Set<String> chapterRefs = new HashSet<>();
            kept.forEach(p -> { refs(p.labels(), "grammar_refs", chapterRefs); refs(p.labels(), "vocab_refs", chapterRefs); });
            double difficulty = kept.stream().mapToInt(p -> p.labels().path("difficulty").asInt(3)).average().orElse(3);
            passages(poolRows.get(), skill, level).stream()
                    .filter(p -> required(p) && p.labels().path("chapter_hint").asInt(-1) == chapter)
                    .sorted(Comparator.comparingInt((Passage p) -> -shared(p.labels(), chapterRefs))
                            .thenComparingDouble(p -> Math.abs(p.labels().path("difficulty").asInt(3) - difficulty))
                            .thenComparing(Passage::group))
                    .limit(missing)
                    .forEach(p -> p.rows().forEach(row -> ids.add(row.getId())));
        }
        return new Result(List.copyOf(ids), ids.isEmpty() && !own.isEmpty() ? "COMPILED_" + skill + "_NOT_READY" : null);
    }

    /** Rows grouped by passage/clip, in the order of their first row (ids ascending, as the repository returns them). */
    private static List<Passage> passages(List<AssessmentQuestion> rows, String skill, String level) {
        Map<String, List<AssessmentQuestion>> groups = new LinkedHashMap<>();
        for (AssessmentQuestion row : rows) {
            String group = CompiledBookPolicy.questionGroup(row.getSourceCode(), skill, level);
            groups.computeIfAbsent(group == null ? row.getSourceCode() : group, ignored -> new ArrayList<>()).add(row);
        }
        List<Passage> out = new ArrayList<>();
        groups.forEach((group, list) -> out.add(new Passage(group, list, labels(list.get(0)))));
        return out;
    }

    /** Accepted for a required part: no labels yet (seeded before §2.32), or an allowed review and not a pool passage. */
    private static boolean required(Passage passage) {
        for (AssessmentQuestion row : passage.rows()) {
            JsonNode context = context(row);
            if (context.path("pool").asBoolean(false)) return false;
            JsonNode labels = context.path("labels");
            if (!labels.isMissingNode() && !CompiledBookPolicy.REQUIRED_REVIEWS.contains(labels.path("review").asText(""))) {
                return false;
            }
        }
        return true;
    }

    private static JsonNode labels(AssessmentQuestion row) {
        return context(row).path("labels");
    }

    private static JsonNode context(AssessmentQuestion row) {
        String text = row.getContextJson();
        if (text == null || text.isBlank()) return JSON.createObjectNode();
        try {
            return JSON.readTree(text);
        } catch (Exception unreadable) {
            return JSON.createObjectNode();
        }
    }

    private static void refs(JsonNode labels, String field, Set<String> into) {
        labels.path(field).forEach(node -> into.add(node.asText()));
    }

    private static int shared(JsonNode labels, Set<String> chapterRefs) {
        Set<String> mine = new HashSet<>();
        refs(labels, "grammar_refs", mine);
        refs(labels, "vocab_refs", mine);
        mine.retainAll(chapterRefs);
        return mine.size();
    }
}
