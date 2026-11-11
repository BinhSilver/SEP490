package com.jlas.assessment.service.impl;

import com.atilika.kuromoji.ipadic.Tokenizer;
import com.jlas.assessment.dto.JlptExamResponse;
import com.jlas.assessment.dto.JlptStudyResponse;
import com.jlas.assessment.dto.JlptStudyResponse.Item;
import com.jlas.assessment.dto.JlptStudyResponse.Source;
import com.jlas.assessment.service.JlptService;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.repository.*;
import com.jlas.dictionary.util.DictionaryUtil;
import java.util.*;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds study lists from actual exam text and the published dictionary/catalog.
 * No generated meanings or invented answer keys are stored or returned. */
@Service
@RequiredArgsConstructor
public class JlptStudyService {
    private final JlptService exams;
    private final VocabularyEntryRepository vocabulary;
    private final VocabularySenseRepository senses;
    private final VocabularyGlossRepository glosses;
    private final GrammarPointRepository grammar;
    private final JlptCuratedStudy curated;
    private static final ThreadLocal<Tokenizer> TOKENIZERS = ThreadLocal.withInitial(Tokenizer::new);
    private static final Pattern UNDERLINE = Pattern.compile("<(?:u|strong)\\b[^>]*>(.*?)</(?:u|strong)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Transactional(readOnly = true)
    public JlptStudyResponse list(String code) {
        var exam = exams.getExam(code); // Existing exam visibility/404 rules apply.
        var saved = curated.find(code);
        if (saved.isPresent()) return fromCurated(exam, saved.get());
        Map<String, Candidate> words = new LinkedHashMap<>();
        Map<String, Candidate> patterns = new LinkedHashMap<>();
        for (var part : exam.parts()) for (var mondai : part.mondai()) for (var q : mondai.questions()) {
            String type = studyType(exam.level(), part.code(), mondai.number(), q.questionType());
            var source = new Source(q.id(), q.number(), part.code(), q.questionType(), plain(q.text()));
            // Some JLPT imports group grammar mondai under vocabulary.
            if (Set.of("GRAMMAR_FORM", "TEXT_GRAMMAR").contains(type)) {
                for (var option : q.options()) add(patterns, plain(option.text()), source, true,
                        Objects.equals(q.correctOption(), option.number()));
            } else if ("SENTENCE_COMPOSITION".equals(type)) {
                if (q.correctOrder() == null || q.correctOrder().size() != 4) continue;
                // The star answer is a fragment, not a complete grammar pattern.
                String joined = q.correctOrder().stream().map(n -> q.options().stream()
                        .filter(o -> o.number() == n).findFirst().map(o -> plain(o.text())).orElse(""))
                        .reduce("", String::concat);
                add(patterns, joined, source, true, false);
            } else if ("vocabulary".equals(part.code())) {
                collectWords(words, plain(nonblank(q.html(), q.text())), source, false);
                var targets = UNDERLINE.matcher(nonblank(q.html(), ""));
                while (targets.find()) add(words, plain(targets.group(1)), source, true, false);
                for (var option : q.options()) {
                    String text = plain(option.text());
                    collectWords(words, text, source, false);
                    // Reading/orthography options are not separate vocabulary targets.
                    if (Objects.equals(q.correctOption(), option.number()) &&
                            !Set.of("KANJI_READING", "ORTHOGRAPHY").contains(type)) {
                        add(words, text, source, true, false);
                    }
                }
            }
        }
        var keys = new ArrayList<>(words.keySet());
        Map<String, VocabularyEntry> dictionary = new HashMap<>();
        for (int start = 0; start < keys.size(); start += 400) {
            vocabulary.findByNormalizedExpressionInAndDictionaryStatus(keys.subList(start, Math.min(keys.size(), start + 400)), "PUBLISHED")
                    .stream().sorted(Comparator.comparing(VocabularyEntry::getId)).forEach(v -> dictionary.putIfAbsent(normalize(v.getExpression()), v));
        }
        Map<Long, String> meanings = meanings(dictionary.values());
        List<Item> wordItems = new ArrayList<>();
        words.forEach((key, candidate) -> {
            var v = dictionary.get(key);
            if (v == null && !candidate.asked) return; // Don't expose incidental tokenizer fragments.
            wordItems.add(new Item("v:" + key, v == null ? candidate.title : v.getExpression(),
                    v == null ? null : v.getReading(), v == null ? null : nonblank(v.getMeaning(), meanings.get(v.getId())),
                    v == null ? null : v.getExampleJa(), v == null ? null : v.getExampleVi(), null,
                    v == null ? null : v.getJlptLevel(), candidate.asked, false, List.copyOf(candidate.sources.values())));
        });
        Map<String, Item> grammarItems = new LinkedHashMap<>();
        var catalog = grammar.findByLesson_StatusOrderByIdAsc("PUBLISHED");
        patterns.forEach((key, candidate) -> {
            List<GrammarPoint> matches = catalog.stream().filter(g -> matches(g.getPattern(), candidate.title)).toList();
            if (matches.isEmpty()) {
                grammarItems.put("raw:" + key, new Item("raw:" + key, candidate.title, null, null, null, null, null, null,
                        true, candidate.correct, List.copyOf(candidate.sources.values())));
            } else for (var g : matches) {
                String id = "g:" + g.getId();
                var prior = grammarItems.get(id);
                Map<Long, Source> sources = new LinkedHashMap<>();
                if (prior != null) prior.sources().forEach(s -> sources.put(s.questionId(), s));
                sources.putAll(candidate.sources);
                grammarItems.put(id, new Item(id, g.getPattern(), null, g.getMeaning(), g.getExample(), null,
                        g.getNote(), null, true, candidate.correct || prior != null && prior.correctAnswer(), List.copyOf(sources.values())));
            }
        });
        return new JlptStudyResponse(exam.code(), exam.title(), exam.level(), wordItems, new ArrayList<>(grammarItems.values()));
    }

    private JlptStudyResponse fromCurated(JlptExamResponse exam, com.fasterxml.jackson.databind.JsonNode saved) {
        List<Item> words = new ArrayList<>(), patterns = new ArrayList<>();
        var catalog = grammar.findByLesson_StatusOrderByIdAsc("PUBLISHED");
        int index = 0;
        for (var word : saved.path("vocabulary")) {
            String title = word.path("word").asText();
            var example = word.path("examples").path(0);
            String type = word.path("question_type").asText().toUpperCase(Locale.ROOT);
            if ("READING".equals(type)) type = "KANJI_READING";
            if ("UNKNOWN".equals(type)) type = "";
            List<Source> sources = new ArrayList<>();
            for (var part : exam.parts()) if ("vocabulary".equals(part.code())) {
                for (var m : part.mondai()) for (var q : m.questions()) {
                    if (!type.isBlank() && !type.equals(normalizedType(q.questionType()))) continue;
                    String question = plain(nonblank(q.html(), q.text()));
                    boolean found = normalize(question).contains(normalize(title)) || q.options().stream()
                            .anyMatch(o -> normalize(plain(o.text())).contains(normalize(title)));
                    if (!found && "exam".equals(example.path("source").asText())) {
                        found = sentence(question).equals(sentence(example.path("ja").asText()));
                    }
                    if (found) sources.add(new Source(q.id(), q.number(), part.code(), q.questionType(), title));
                }
            }
            words.add(new Item("cv:" + index++, title, word.path("reading").asText(null), word.path("meaning_vi").asText(null),
                    example.path("ja").asText(null), example.path("vi").asText(null), null, null,
                    word.path("tested").asBoolean(), false, sources, word.path("han_viet").asText(null),
                    word.path("question_type").asText(null), null));
        }
        index = 0;
        for (var ref : saved.path("grammar")) {
            String title = ref.path("pattern").asText();
            var lesson = ref.path("lesson");
            String meaning = lesson.path("meaning").asText(null), note = lesson.path("note").asText(null);
            var example = lesson.path("usages").path(0).path("examples").path(0);
            String exampleJa = example.path("ja").asText(null), exampleVi = example.path("vi").asText(null);
            if (meaning == null) {
                var match = catalog.stream().filter(g -> matches(g.getPattern(), title.replaceAll("[〜～]", ""))).findFirst();
                if (match.isPresent()) { meaning = match.get().getMeaning(); note = match.get().getNote(); exampleJa = match.get().getExample(); }
            }
            List<Source> sources = new ArrayList<>();
            String prompt = sentence(ref.path("question").path("text").asText());
            List<String> expressions = new ArrayList<>();
            for (var expression : ref.path("expressions")) expressions.add(expression.asText());
            String surfaces = expressions.isEmpty() ? title : String.join(" / ", expressions);
            // N1/N2 seeds can place grammar mondai in the combined vocabulary part.
            for (var part : exam.parts()) if (!"listening".equals(part.code())) {
                for (var m : part.mondai()) for (var q : m.questions()) {
                    if (!prompt.isEmpty() && (sentence(q.text()).equals(prompt) || sentence(q.html()).equals(prompt)))
                        sources.add(new Source(q.id(), q.number(), part.code(), q.questionType(), surfaces));
                }
            }
            patterns.add(new Item("cg:" + index++, title, null, meaning, exampleJa, exampleVi, note,
                    ref.path("level").asText(null), true, ref.path("is_answer").asBoolean(), sources,
                    null, null, ref.path("link").asText(null), expressions));
        }
        return new JlptStudyResponse(exam.code(), exam.title(), exam.level(), words, patterns);
    }
    private static String sentence(String text) {
        return normalize(plain(text)).replaceFirst("^\\s*\\d+[.．、)）]\\s*", "").replaceAll("[\\s　]", "");
    }

    private Map<Long, String> meanings(Collection<VocabularyEntry> entries) {
        var ids = entries.stream().map(VocabularyEntry::getId).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        var found = senses.findByVocabularyEntry_IdInOrderByVocabularyEntry_IdAscSenseOrderAsc(ids);
        var senseIds = found.stream().map(s -> s.getId()).toList();
        if (senseIds.isEmpty()) return Map.of();
        Map<Long, List<String>> vi = new HashMap<>(), en = new HashMap<>();
        for (var gloss : glosses.findBySense_IdIn(senseIds)) {
            var target = "vi".equals(gloss.getLanguageCode()) ? vi : "en".equals(gloss.getLanguageCode()) ? en : null;
            if (target != null) target.computeIfAbsent(gloss.getSense().getVocabularyEntry().getId(), id -> new ArrayList<>()).add(gloss.getGlossText());
        }
        Map<Long, String> result = new HashMap<>();
        ids.forEach(id -> { var values = vi.getOrDefault(id, en.getOrDefault(id, List.of()));
            if (!values.isEmpty()) result.put(id, String.join("; ", values.stream().distinct().limit(8).toList())); });
        return result;
    }

    private void collectWords(Map<String, Candidate> words, String text, Source source, boolean asked) {
        var tokens = TOKENIZERS.get().tokenize(text);
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (!Set.of("名詞", "動詞", "形容詞", "副詞", "連体詞", "接続詞").contains(token.getPartOfSpeechLevel1())) continue;
            String base = token.getBaseForm();
            add(words, base == null || "*".equals(base) ? token.getSurface() : base, source, asked, false);
            String compound = "";
            for (int j = i; j < Math.min(i + 4, tokens.size()); j++) {
                if (tokens.get(j).getSurface().matches(".*[\\s、。！？_＿★].*")) break;
                compound += tokens.get(j).getSurface();
                add(words, compound, source, false, false);
            }
        }
    }
    private void add(Map<String, Candidate> values, String title, Source source, boolean asked, boolean correct) {
        title = title.replaceFirst("^\\s*[1-4][.．、)）]\\s*", "").trim();
        if (title.isEmpty() || title.length() > 200 || !title.matches(".*[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}].*")) return;
        String finalTitle = title;
        var candidate = values.computeIfAbsent(normalize(title), k -> new Candidate(finalTitle));
        candidate.asked |= asked; candidate.correct |= correct;
        candidate.sources.putIfAbsent(source.questionId(), new Source(source.questionId(), source.number(),
                source.part(), source.type(), title));
    }
    static boolean matches(String pattern, String text) {
        // Match literal catalog endings against complete options; avoid interpreting arbitrary regex.
        for (String alternative : pattern.split("[、,／/]")) {
            String literal = alternative.substring(alternative.lastIndexOf('+') + 1)
                    .replaceAll("[（(][^）)]*[）)]", "").replaceAll("[〜～~\\s]", "");
            if (literal.length() >= 2 && literal.matches("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー]+")
                    && normalize(text).endsWith(normalize(literal))) return true;
        }
        return false;
    }
    static String plain(String html) {
        if (html == null) return "";
        return html.replaceAll("(?is)<(rt|rp|script|style)\\b[^>]*>.*?</\\1>", "")
                .replaceAll("<[^>]+>", "").replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replaceAll("\\s+", " ").trim();
    }
    private static String normalize(String text) { return DictionaryUtil.normalizeReading(text); }
    private static String normalizedType(String type) { return Objects.toString(type, "").toUpperCase(Locale.ROOT); }
    private static String studyType(String level, String part, int mondai, String questionType) {
        String type = normalizedType(questionType);
        if (!"N4".equals(level) || !"grammar_reading".equals(part) || !"MULTIPLE_CHOICE".equals(type)) return type;
        // The N4 PDF importer uses one type for grammar and reading, but keeps the original mondai numbers.
        return switch (mondai) {
            case 1 -> "GRAMMAR_FORM";
            case 2 -> "SENTENCE_COMPOSITION";
            case 3 -> "TEXT_GRAMMAR";
            default -> type;
        };
    }
    private static String nonblank(String a, String b) { return a == null || a.isBlank() ? b : a; }
    private static class Candidate {
        final String title; boolean asked, correct;
        final Map<Long, Source> sources = new LinkedHashMap<>();
        Candidate(String title) { this.title = title; }
    }
}
