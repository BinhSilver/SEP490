package com.jlas.learning.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.repository.GrammarPointRepository;
import com.jlas.learning.ai.AiJsonGenerator;
import com.jlas.learning.dto.GrammarExtraPractice;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.service.impl.grammar.GrammarExampleHold;
import com.jlas.learning.service.impl.grammar.GrammarPhraseSegmenter;
import com.jlas.learning.service.impl.grammar.GrammarPhraseSegmenter.Practice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** [JLAS-W0108] Owner 2026-10-21: three extra AI-written practice sentences per grammar pattern, for every path.
 *
 * <p>Optional and unscored: nothing is saved to the learner's progress and completion never depends on it. The AI only
 * writes sentences; each one must contain the pattern (or its leading part when conjugated) and is cut into tiles by the
 * same deterministic segmenter as the lesson's own example. Results are cached per pattern so the provider is called once
 * per pattern, not once per learner. Labelled as AI content that is not reviewed. */
@Service
@RequiredArgsConstructor
public class GrammarExtraPracticeService {
    static final String LABEL = "Câu luyện thêm do AI tạo · chưa rà soát";
    static final int WANTED = 3;
    private static final int MAX_CACHED_POINTS = 2_000;

    private final UserLearningPathItemRepository items;
    private final LearningActivityMemberRepository members;
    private final GrammarPointRepository grammarPoints;
    private final AiJsonGenerator ai;
    private final Map<Long, List<GrammarExtraPractice.Item>> cache = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public GrammarExtraPractice generate(Long userId, Long activityId, Long grammarPointId) {
        UserLearningPathItem activity = items.findByIdAndUserId(activityId, userId)
                .filter(item -> item.getActivityType() == LearningActivityType.GRAMMAR)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        InteractiveActivityStatePolicy.requireUnlocked(activity);
        boolean member = members.findByActivityIdOrderByOrderIndex(activity.getId()).stream()
                .anyMatch(m -> m.getMemberType() == LearningMemberType.GRAMMAR_POINT && grammarPointId.equals(m.getCanonicalId()));
        if (!member) throw new ApiException(ErrorCode.NOT_FOUND);
        GrammarPoint point = grammarPoints.findById(grammarPointId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

        List<GrammarExtraPractice.Item> cached = cache.get(grammarPointId);
        if (cached != null) return new GrammarExtraPractice(LABEL, cached);
        List<GrammarExtraPractice.Item> made = sentences(point);
        if (made.isEmpty()) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Chưa tạo được câu luyện thêm lúc này. Vui lòng thử lại sau.");
        }
        if (cache.size() < MAX_CACHED_POINTS) cache.put(grammarPointId, made);
        return new GrammarExtraPractice(LABEL, made);
    }

    private List<GrammarExtraPractice.Item> sentences(GrammarPoint point) {
        String pattern = point.getPattern();
        String example = firstLine(point.getExample());
        if (GrammarExampleHold.held(pattern, example)) example = null;
        JsonNode reply = ai.generateJson(SYSTEM_PROMPT, userPrompt(pattern, firstLine(point.getMeaning()), example));
        JsonNode list = reply == null ? null : reply.path("items");
        if (list == null || !list.isArray()) return List.of();
        return accept(list, pattern, example);
    }

    /** Keeps at most {@link #WANTED} distinct sentences that really use the pattern and cut into at least three tiles. */
    static List<GrammarExtraPractice.Item> accept(JsonNode list, String pattern, String example) {
        String core = GrammarPhraseSegmenter.patternCore(pattern);
        Set<String> seen = new LinkedHashSet<>();
        if (example != null) seen.add(example.trim());
        List<GrammarExtraPractice.Item> out = new ArrayList<>();
        for (JsonNode node : list) {
            if (out.size() >= WANTED) break;
            String ja = node.path("ja").asText("").trim();
            String vi = node.path("vi").asText("").trim();
            if (ja.length() < 6 || ja.length() > 60 || !ja.matches(".*[\\u3040-\\u30ff\\u4e00-\\u9fff].*") || !seen.add(ja)) continue;
            if (!usesPattern(ja, core)) continue;
            Practice practice = GrammarPhraseSegmenter.practice(ja, pattern);
            if (practice.parts().size() < 3 || practice.blankIndex() < 0) continue;
            out.add(new GrammarExtraPractice.Item(ja, vi.isEmpty() ? null : vi, practice.parts(), practice.spaced(),
                    practice.prompt(), practice.answer()));
        }
        return out;
    }

    private static boolean usesPattern(String sentence, String core) {
        if (core == null || core.isBlank()) return false;
        if (sentence.contains(core)) return true;
        String bare = GrammarPhraseSegmenter.withoutLeadingParticle(core);
        if (bare.length() >= 2 && sentence.contains(bare)) return true;
        return core.length() >= 3 && sentence.contains(core.substring(0, core.length() - 1));
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) return null;
        int newline = text.indexOf('\n');
        return (newline < 0 ? text : text.substring(0, newline)).trim();
    }

    private static final String SYSTEM_PROMPT = "You write short, natural Japanese practice sentences for Vietnamese "
            + "learners of Japanese. Reply with JSON only, no commentary.";

    static String userPrompt(String pattern, String meaningVi, String example) {
        return "Grammar pattern: 「" + pattern + "」" + (meaningVi == null ? "" : " (Vietnamese meaning: " + meaningVi + ")") + ".\n"
                + (example == null ? "" : "The lesson already uses this example, do not repeat it: " + example + "\n")
                + "Write 5 NEW, different, natural sentences that each use this exact pattern, in polite everyday Japanese "
                + "(work, travel or daily life). 8 to 40 Japanese characters each, no romaji, no furigana, no brackets.\n"
                + "Give each sentence a short natural Vietnamese translation.\n"
                + "JSON shape: {\"items\":[{\"ja\":\"...\",\"vi\":\"...\"}]}";
    }
}
