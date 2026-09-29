package com.jlas.kaiwa.service.learning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.kaiwa.dto.FuriganaTokenDto;
import com.jlas.kaiwa.model.ScenarioVocabulary;
import com.jlas.kaiwa.model.TextAnnotationCache;
import com.jlas.kaiwa.repository.ScenarioVocabularyRepository;
import com.jlas.kaiwa.repository.TextAnnotationCacheRepository;
import com.jlas.kaiwa.service.ai.OpenRouterClient;
import com.jlas.kaiwa.service.scenario.ScenarioContentService;
import com.jlas.shadowing.model.VocabularyEntry;
import com.jlas.shadowing.repository.VocabularyEntryRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Furigana + translation: Vocabulary DB first  AI  cache  reuse.
 */
@Service
public class AnnotationService {
    private static final Pattern KANJI = Pattern.compile("[\\u4E00-\\u9FFF々〆ヵヶ]+");

    private final ScenarioVocabularyRepository vocabularyRepository;
    private final VocabularyEntryRepository dictionaryRepository;
    private final TextAnnotationCacheRepository cacheRepository;
    private final OpenRouterClient openRouterClient;
    private final ObjectMapper objectMapper;
    private final ScenarioContentService scenarioContent;

    public AnnotationService(
            ScenarioVocabularyRepository vocabularyRepository,
            VocabularyEntryRepository dictionaryRepository,
            TextAnnotationCacheRepository cacheRepository,
            OpenRouterClient openRouterClient,
            ObjectMapper objectMapper,
            ScenarioContentService scenarioContent) {
        this.vocabularyRepository = vocabularyRepository;
        this.dictionaryRepository = dictionaryRepository;
        this.cacheRepository = cacheRepository;
        this.openRouterClient = openRouterClient;
        this.objectMapper = objectMapper;
        this.scenarioContent = scenarioContent;
    }

    /**
     * @param allowAi if false (default for chat path), only Vocabulary DB + cache — no OpenRouter.
     *                if true, fill remaining kanji via AI then cache.
     */
    @Transactional
    public List<FuriganaTokenDto> annotateFurigana(Long scenarioId, String text) {
        return annotateFurigana(scenarioId, text, false);
    }

    @Transactional
    public List<FuriganaTokenDto> annotateFurigana(Long scenarioId, String text, boolean allowAi) {
        if (text == null || text.isBlank()) return List.of();
        String hash = sha256(text.trim());

        Optional<TextAnnotationCache> cached = cacheRepository.findByContentHash(hash);
        List<ScenarioVocabulary> vocab = lexicon(scenarioId, text.trim());
        Map<String, ScenarioVocabulary> byWord = vocab.stream()
                .filter(v -> v.getWordJa() != null && !v.getWordJa().isBlank())
                .sorted(Comparator.comparingInt((ScenarioVocabulary v) -> v.getWordJa().length()).reversed())
                .collect(Collectors.toMap(ScenarioVocabulary::getWordJa, v -> v, (a, b) -> a, LinkedHashMap::new));

        List<FuriganaTokenDto> tokens = segmentWithVocab(text.trim(), byWord);
        boolean hasDictionaryMatch = tokens.stream().anyMatch(FuriganaTokenDto::isFromVocabDb);
        if (!hasDictionaryMatch && cached.isPresent() && cached.get().getFuriganaJson() != null) {
            try {
                tokens = objectMapper.readValue(cached.get().getFuriganaJson(), new TypeReference<>() {});
                enrichFromVocab(scenarioId, tokens);
                return tokens;
            } catch (Exception ignored) {
            }
        }
        boolean needsAi = tokens.stream().anyMatch(t ->
                hasKanji(t.getSurface()) && (t.getReading() == null || t.getReading().isBlank()));

        // Prefer rich scenario_vocabulary seed — only call AI when explicitly allowed
        if (needsAi && allowAi) {
            List<FuriganaTokenDto> aiTokens = callAiFurigana(text.trim());
            if (!aiTokens.isEmpty()) {
                mergePreferDb(tokens, aiTokens, byWord);
                tokens = aiTokens;
            }
        }

        enrichFromVocab(scenarioId, tokens);
        // Cache vocab-only result too so next time is free
        saveCache(hash, text.trim(), tokens, null, (needsAi && allowAi) ? "ai" : "vocab_merge");
        return tokens;
    }

    @Transactional
    public String translate(Long scenarioId, String text) {
        if (text == null || text.isBlank()) return "";
        String hash = sha256(text.trim());
        Optional<TextAnnotationCache> cached = cacheRepository.findByContentHash(hash);
        if (cached.isPresent() && cached.get().getTranslationVi() != null
                && !cached.get().getTranslationVi().isBlank()) {
            return cached.get().getTranslationVi();
        }

        // Exact vocab hit
        if (scenarioId != null) {
            Optional<ScenarioVocabulary> v = vocabulary(scenarioId)
                    .stream().filter(x -> text.trim().equals(x.getWordJa())).findFirst();
            if (v.isPresent()) {
                saveCache(hash, text.trim(), null, v.get().getMeaning(), "vocab_merge");
                return v.get().getMeaning();
            }
        }

        String vi = callAiTranslate(text.trim());
        TextAnnotationCache row = cacheRepository.findByContentHash(hash).orElseGet(TextAnnotationCache::new);
        row.setContentHash(hash);
        row.setContentJa(text.trim());
        if (row.getFuriganaJson() == null) {
            // keep existing furigana if any
        }
        row.setTranslationVi(vi);
        row.setSource("ai");
        cacheRepository.save(row);
        return vi;
    }

    private List<FuriganaTokenDto> segmentWithVocab(String text, Map<String, ScenarioVocabulary> byWord) {
        List<FuriganaTokenDto> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            String word = longestMatch(text, i, byWord.keySet());
            if (word != null) {
                ScenarioVocabulary v = byWord.get(word);
                FuriganaTokenDto t = new FuriganaTokenDto(word, v.getReading());
                t.setMeaning(v.getMeaning());
                t.setPartOfSpeech(v.getPartOfSpeech());
                t.setJlptLevel(v.getJlptLevel());
                t.setFromVocabDb(true);
                out.add(t);
                i += word.length();
                continue;
            }

            // Keep unknown text as one natural fragment instead of making every
            // kana/punctuation character look like a separate dictionary word.
            int end = i + Character.charCount(text.codePointAt(i));
            while (end < text.length() && longestMatch(text, end, byWord.keySet()) == null) {
                end += Character.charCount(text.codePointAt(end));
            }
            String fragment = text.substring(i, end);
            out.add(new FuriganaTokenDto(fragment, hasKanji(fragment) ? null : ""));
            i = end;
        }
        return out;
    }

    private static String longestMatch(String text, int offset, Set<String> words) {
        for (String word : words) {
            if (text.startsWith(word, offset)) return word;
        }
        return null;
    }

    private void mergePreferDb(List<FuriganaTokenDto> dbSeg, List<FuriganaTokenDto> aiTokens,
                               Map<String, ScenarioVocabulary> byWord) {
        for (FuriganaTokenDto t : aiTokens) {
            ScenarioVocabulary v = byWord.get(t.getSurface());
            if (v != null && v.getReading() != null && !v.getReading().isBlank()) {
                t.setReading(v.getReading());
                t.setFromVocabDb(true);
                t.setMeaning(v.getMeaning());
                t.setPartOfSpeech(v.getPartOfSpeech());
                t.setJlptLevel(v.getJlptLevel());
            }
        }
    }

    private void enrichFromVocab(Long scenarioId, List<FuriganaTokenDto> tokens) {
        if (scenarioId == null || tokens == null) return;
        Map<String, ScenarioVocabulary> map = vocabulary(scenarioId).stream()
                .collect(Collectors.toMap(ScenarioVocabulary::getWordJa, v -> v, (a, b) -> a));
        for (FuriganaTokenDto t : tokens) {
            ScenarioVocabulary v = map.get(t.getSurface());
            if (v != null) {
                if (v.getReading() != null && !v.getReading().isBlank()) {
                    t.setReading(v.getReading());
                    t.setFromVocabDb(true);
                }
                t.setMeaning(v.getMeaning());
                t.setPartOfSpeech(v.getPartOfSpeech());
                t.setJlptLevel(v.getJlptLevel());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<FuriganaTokenDto> callAiFurigana(String text) {
        try {
            String system = """
                    You annotate Japanese with furigana as JSON array only.
                    Return: [{"surface":"何名","reading":"なんめい"},{"surface":"様","reading":"さま"},{"surface":"ですか？","reading":""}]
                    Rules: split into tokens; kana-only surface has empty reading; no markdown.
                    """;
            String raw = openRouterClient.complete(system, List.of(), text);
            String json = extractJsonArray(raw);
            List<Map<String, String>> list = objectMapper.readValue(json, new TypeReference<>() {});
            List<FuriganaTokenDto> tokens = new ArrayList<>();
            for (Map<String, String> m : list) {
                FuriganaTokenDto t = new FuriganaTokenDto(
                        m.getOrDefault("surface", ""),
                        m.get("reading") == null ? "" : m.get("reading"));
                tokens.add(t);
            }
            return tokens;
        } catch (Exception e) {
            return List.of(new FuriganaTokenDto(text, ""));
        }
    }

    private String callAiTranslate(String text) {
        try {
            String system = "Translate Japanese to Vietnamese. Return only the Vietnamese translation, no quotes.";
            return openRouterClient.complete(system, List.of(), text).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void saveCache(String hash, String text, List<FuriganaTokenDto> tokens, String translation, String source) {
        try {
            TextAnnotationCache row = cacheRepository.findByContentHash(hash).orElseGet(TextAnnotationCache::new);
            row.setContentHash(hash);
            row.setContentJa(text);
            if (tokens != null) {
                row.setFuriganaJson(objectMapper.writeValueAsString(tokens));
            }
            if (translation != null) {
                row.setTranslationVi(translation);
            }
            row.setSource(source);
            cacheRepository.save(row);
        } catch (Exception ignored) {
        }
    }

    private static String extractJsonArray(String raw) {
        int a = raw.indexOf('[');
        int b = raw.lastIndexOf(']');
        if (a >= 0 && b > a) return raw.substring(a, b + 1);
        return raw;
    }

    private static boolean hasKanji(String s) {
        return s != null && KANJI.matcher(s).find();
    }

    private List<ScenarioVocabulary> vocabulary(Long scenarioId) {
        List<ScenarioVocabulary> compact = scenarioContent.vocabulary(scenarioId);
        return compact.isEmpty()
                ? vocabularyRepository.findByScenarioIdOrderByDisplayOrderAsc(scenarioId)
                : compact;
    }

    private List<ScenarioVocabulary> lexicon(Long scenarioId, String text) {
        List<ScenarioVocabulary> result = new ArrayList<>();
        if (scenarioId != null) result.addAll(vocabulary(scenarioId));

        Set<String> existing = result.stream()
                .map(ScenarioVocabulary::getWordJa)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        int order = result.size();
        for (VocabularyEntry entry : dictionaryRepository.findPublishedContainedIn(text)) {
            if (entry.getExpression() == null || entry.getExpression().isBlank()
                    || !existing.add(entry.getExpression())) continue;
            ScenarioVocabulary item = new ScenarioVocabulary();
            item.setWordJa(entry.getExpression());
            item.setReading(entry.getReading());
            item.setMeaning(entry.getMeaning());
            item.setPartOfSpeech(entry.getWordType());
            item.setJlptLevel(entry.getJlptLevel());
            item.setExampleJa(entry.getExampleJa());
            item.setDisplayOrder(++order);
            result.add(item);
        }
        return result;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
