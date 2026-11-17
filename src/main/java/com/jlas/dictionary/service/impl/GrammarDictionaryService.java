package com.jlas.dictionary.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.dto.PageResponse;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.repository.GrammarPointRepository;
import com.jlas.dictionary.util.GrammarText;
import com.jlas.jlptpath.entity.JpathExample;
import com.jlas.jlptpath.entity.JpathGrammar;
import com.jlas.jlptpath.enums.JpathContentStatus;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.repository.JpathExampleRepository;
import com.jlas.jlptpath.repository.JpathGrammarRepository;
import com.jlas.jlptpath.util.JpathAudio;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public dictionary view of imported JLPT grammar and its textbook occurrences. */
@Service
public class GrammarDictionaryService {
    private static final List<JpathContentStatus> VISIBLE = List.of(
            JpathContentStatus.AI_CHECKED, JpathContentStatus.REVIEWED, JpathContentStatus.HOLD);
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private final JpathGrammarRepository grammars;
    private final JpathExampleRepository examples;
    private final GrammarPointRepository textbookGrammar;
    private final ObjectMapper json;

    public GrammarDictionaryService(JpathGrammarRepository grammars, JpathExampleRepository examples,
                                    GrammarPointRepository textbookGrammar, ObjectMapper json) {
        this.grammars = grammars;
        this.examples = examples;
        this.textbookGrammar = textbookGrammar;
        this.json = json;
    }

    public record Summary(String code, String pattern, String level, String meaningVi, boolean unreviewed) {}
    public record Example(String ja, List<GrammarText.Token> tokens, String vi, String audioUrl) {}
    public record Occurrence(String textbookTitle, String level, String lessonTitle, Long lessonId,
                             String url, String pattern, String meaning, String explanation,
                             String exampleJa, String exampleVi) {}
    public record Detail(String code, String pattern, List<String> variants, String level, String meaningVi,
                         String formation, String usageVi, String nuanceVi, String commonMistakesVi,
                         boolean unreviewed, List<Example> examples, List<Summary> related,
                         List<Occurrence> textbookOccurrences) {}

    @Transactional(readOnly = true)
    public PageResponse<Summary> search(String query, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(50, size));
        String normalized = GrammarText.normalize(query);
        if (normalized.isEmpty()) return page(List.of(), safePage, safeSize, 0);
        // Ordered characters select candidates even when a pattern inserts spaces or placeholders.
        // Java then applies exact normalization and ranking to the much smaller candidate set.
        var ranked = grammars.findDictionaryCandidates(subsequence(normalized)).stream()
                .map(grammar -> new Ranked(grammar, GrammarText.rank(grammar,
                        parseStrings(grammar.getPatternVariantsJson()), normalized)))
                .filter(item -> item.rank() != Integer.MAX_VALUE)
                .sorted(Comparator.comparingInt(Ranked::rank)
                        .thenComparingInt(item -> levelOrder(item.grammar().getLevel()))
                        .thenComparingInt(item -> Objects.requireNonNullElse(item.grammar().getSortOrder(), 0))
                        .thenComparing(item -> item.grammar().getCode()))
                .map(item -> summary(item.grammar())).toList();
        long offset = (long) safePage * safeSize;
        int from = (int) Math.min(offset, ranked.size());
        int to = Math.min(from + safeSize, ranked.size());
        return page(ranked.subList(from, to), safePage, safeSize, ranked.size());
    }

    private record Ranked(JpathGrammar grammar, int rank) {}

    private static PageResponse<Summary> page(List<Summary> content, int page, int size, long total) {
        int totalPages = (int) ((total + size - 1) / size);
        return PageResponse.<Summary>builder().content(content).page(page).size(size)
                .totalElements(total).totalPages(totalPages).last(page >= totalPages - 1).build();
    }

    @Transactional(readOnly = true)
    public Detail detail(String code) {
        JpathGrammar grammar = grammars.findById(code).filter(GrammarDictionaryService::visible)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        List<Example> visibleExamples = examples
                .findByOwnerTypeAndOwnerCodeAndStatusInAndReportHiddenFalseOrderBySortOrder(JpathItemType.GRAMMAR, code, VISIBLE)
                .stream().map(GrammarDictionaryService::example).toList();
        List<Summary> related = parseStrings(grammar.getRelatedCodesJson()).stream().distinct()
                .filter(other -> !code.equals(other)).map(grammars::findById).flatMap(java.util.Optional::stream)
                .filter(GrammarDictionaryService::visible).map(GrammarDictionaryService::summary).toList();
        String core = GrammarText.core(grammar.getPattern(), grammar.getFamilyKey());
        List<Occurrence> occurrences = core.isEmpty() ? List.of() : textbookGrammar
                .findPatternCandidates(subsequence(core)).stream()
                .filter(point -> validPoint(point) && GrammarText.normalize(point.getPattern()).contains(core))
                .sorted(Comparator.comparingInt((GrammarPoint point) -> levelOrder(
                                point.getLesson().getUnit().getTextbook().getJlptLevel()))
                        .thenComparing(point -> point.getLesson().getUnit().getTextbook().getTitle())
                        .thenComparing(point -> point.getLesson().getOrderIndex())
                        .thenComparing(GrammarPoint::getOrderIndex))
                .limit(10).map(GrammarDictionaryService::occurrence).toList();
        return new Detail(code, grammar.getPattern(), parseStrings(grammar.getPatternVariantsJson()),
                grammar.getLevel(), grammar.getMeaningVi(), grammar.getFormation(), grammar.getUsageVi(),
                grammar.getNuanceVi(), grammar.getCommonMistakesVi(), unreviewed(grammar),
                visibleExamples, related, occurrences);
    }

    private List<String> parseStrings(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return json.readValue(value, STRINGS); }
        catch (Exception ignored) { return List.of(); }
    }

    private static boolean visible(JpathGrammar grammar) {
        return !grammar.isReportHidden() && VISIBLE.contains(grammar.getStatus());
    }

    private static boolean unreviewed(JpathGrammar grammar) {
        return grammar.getStatus() == JpathContentStatus.HOLD;
    }

    private static Summary summary(JpathGrammar grammar) {
        return new Summary(grammar.getCode(), grammar.getPattern(), grammar.getLevel(),
                firstLine(grammar.getMeaningVi()), unreviewed(grammar));
    }

    private static Example example(JpathExample example) {
        return new Example(example.getJa(), GrammarText.furiganaTokens(example.getJaFurigana(), example.getJa()),
                example.getVi(), JpathAudio.url(example.getAudioPath(), null));
    }

    private static boolean validPoint(GrammarPoint point) {
        String pattern = point.getPattern();
        return pattern != null && !pattern.isBlank() && pattern.length() <= 40 && !pattern.contains("。");
    }

    private static Occurrence occurrence(GrammarPoint point) {
        var lesson = point.getLesson();
        var textbook = lesson.getUnit().getTextbook();
        String[] meaning = Objects.requireNonNullElse(point.getMeaning(), "").split("\\R", 2);
        String[] firstExample = Objects.requireNonNullElse(point.getExample(), "").strip().split("\\R", 3);
        String ja = firstExample.length == 0 ? "" : firstExample[0].strip();
        String vi = firstExample.length > 1 ? firstExample[1].strip() : "";
        int separator = ja.indexOf('|');
        if (separator >= 0) { vi = ja.substring(separator + 1).strip(); ja = ja.substring(0, separator).strip(); }
        return new Occurrence(textbook.getTitle(), textbook.getJlptLevel(), lesson.getTitle(), lesson.getId(),
                "/textbooks/" + textbook.getId() + "/lessons/" + lesson.getId(), point.getPattern(),
                firstLine(point.getMeaning()), meaning.length > 1 ? meaning[1].strip() : "", ja, vi);
    }

    private static String firstLine(String text) {
        return text == null ? "" : text.split("\\R", 2)[0].strip();
    }

    private static String subsequence(String core) {
        return "%" + core.codePoints().mapToObj(Character::toString)
                .map(value -> value.replace("!", "!!").replace("%", "!%").replace("_", "!_"))
                .collect(java.util.stream.Collectors.joining("%")) + "%";
    }

    private static int levelOrder(String level) {
        if (level == null || !level.matches("N[1-5]")) return 9;
        return 5 - (level.charAt(1) - '0');
    }
}
