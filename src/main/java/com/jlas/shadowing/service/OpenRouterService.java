package com.jlas.shadowing.service;

import com.jlas.shadowing.model.FuriganaCache;
import com.jlas.shadowing.repository.FuriganaCacheRepository;
import com.jlas.shadowing.util.SubtitleText;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class OpenRouterService {

    private static final AtomicInteger DUMP_SEQ = new AtomicInteger(0);

    private static final String SYSTEM_PROMPT = """
            You are an expert Japanese linguist specializing in furigana annotation, Japanese proper names, and natural translation.

            For each numbered Japanese sentence, return:
            1. Furigana using the format: 漢字(かんじ)
            2. A natural translation in the target language specified in the user message.

            Return ONLY a JSON array.

            Example:
            [
              {
                "furigana":"今日(きょう)は天気(てんき)がいいですね。",
                "translation":"Hôm nay thời tiết đẹp nhỉ."
              }
            ]

            STRICT RULES

            1. Preserve the original sentence exactly.
               - Do NOT rewrite.
               - Do NOT simplify.
               - Do NOT correct grammar.
               - Do NOT add or remove punctuation.

            2. Add furigana ONLY to kanji.
               Hiragana, katakana, numbers and punctuation must remain unchanged.
               The "furigana" field must be the FULL sentence with inline 漢字(かんじ) annotations.

            3. Proper names are extremely important.
               If a word is likely to be a person's name, family name, place name, company, school,
               anime title, game title, product name, or organization,
               use the most common Japanese reading.

               Examples:
               山田  山田(やまだ)
               佐藤  佐藤(さとう)
               東京  東京(とうきょう)
               大阪  大阪(おおさか)
               任天堂  任天堂(にんてんどう)

               Never translate a proper noun into its literal meaning.

               Example (person name):
               Correct: 希望(のぞみ)
               Wrong: 希望(きぼう)

            4. If a kanji has multiple possible readings, choose the reading that fits sentence context.
               Examples: 今日  きょう; 一日  ついたち or いちにち; 生  せい / しょう / なま / う

            5. If the reading is uncertain, prefer the most common pronunciation used by native speakers.
               Never invent rare readings.

            6. Translation must be natural. Do NOT translate word-by-word. Translate as a native speaker would.

            7. Preserve honorifics: 先生, さん, ちゃん, くん, 様.
               Do NOT omit them unless the target language naturally keeps them.

            8. Keep Japanese quotation marks, emoji, symbols, numbers, and punctuation unchanged.

            9. Return valid JSON only. No markdown. No explanation. No comments. No extra text.
               Never wrap the JSON inside another object.

            10. Never start furigana or translation with sentence-final punctuation alone
               (e.g. leading "。" or "."). If the source begins with "。", treat it as STT noise
               and omit it from both fields.

            BATCH PROCESSING
            - Preserve the exact input order.
            - Never skip any sentence.
            - The output array length MUST exactly equal the input sentence count.
            - If a sentence cannot be processed, still return {"furigana":"","translation":""}.
            - Never stop processing remaining sentences.

            WRONG furigana (never output):
            - Plain kanji without readings: 今日は天気がいいですね
            - Partial sentence only: 今日(きょう)
            - 【】 [] {} <> HTML <ruby> or kana-only annotations

            Do NOT hallucinate. Do NOT infer missing words. Do NOT normalize Japanese.
            Do NOT convert full-width to half-width. Do NOT convert kana.
            Keep every character exactly as provided. Only insert furigana after Kanji.
            """;

    @Value("${openrouter.api.key}")
    private String apiKey;

    @Value("${openrouter.site.url:http://localhost:8080}")
    private String siteUrl;

    /** Shadowing furigana/translate only — other modules keep openrouter.model */
    @Value("${openrouter.shadowing.model:${openrouter.model:openai/gpt-4o-mini-2024-07-18}}")
    private String model;

    @Value("${openrouter.temperature:0}")
    private double temperature;

    @Value("${openrouter.top-p:0.9}")
    private double topP;

    /**
     * Cap completion tokens so OpenRouter does not reserve the full model max (32k/65k),
     * which causes 402 when the key only has partial credits.
     * Long JP lines + furigana need headroom — 3k was truncating mid-JSON.
     */
    @Value("${openrouter.shadowing.max-tokens:8192}")
    private int maxTokens;

    @Value("${processing.ai.json.max-retries:3}")
    private int jsonMaxRetries;

    @Value("${upload.path.video:uploads/video/}")
    private String videoUploadPath;

    @Autowired
    private FuriganaCacheRepository furiganaCacheRepository;

    @Autowired(required = false)
    private com.jlas.common.ai.AiUsageRecorder usageRecorder;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenRouterService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(55_000);
        this.restTemplate = new RestTemplate(factory);
        this.restTemplate.getMessageConverters().add(0,
                new StringHttpMessageConverter(Objects.requireNonNull(StandardCharsets.UTF_8)));
    }

    public Map<String, String> processJapanese(String text, String targetLanguage) {
        return processJapanese(text, targetLanguage, true);
    }

    public Map<String, String> processJapanese(String text, String targetLanguage, boolean generateFurigana) {
        String clean = SubtitleText.normalizeJapanese(text);
        String lang = targetLanguage != null ? targetLanguage : "vi";

        Optional<FuriganaCache> cached = furiganaCacheRepository.findByOriginalTextAndTargetLanguage(clean, lang);
        if (cached.isPresent()) {
            Map<String, String> result = new HashMap<>();
            result.put("furigana", generateFurigana ? cached.get().getFurigana() : clean);
            result.put("translation", cached.get().getTranslation());
            return result;
        }

        Map<String, String> result = processJapaneseBatch(List.of(clean), lang, generateFurigana).get(0);
        saveCache(clean, lang, result);
        return result;
    }

    /** Explicit user regeneration bypasses cache and never replaces saved fields with a fallback. */
    public Map<String, String> regenerateJapanese(String text, String targetLanguage) {
        String clean = SubtitleText.normalizeJapanese(text);
        if (clean.isBlank()) throw new ApiException(ErrorCode.BAD_REQUEST, "Hãy nhập câu tiếng Nhật trước khi tạo lại AI.");
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "AI chưa được cấu hình. Hãy thử lại sau.");
        }
        String lang = targetLanguage == null ? "vi" : targetLanguage;
        FetchOutcome outcome = fetchBatchWithJsonRetry(List.of(clean), lang, true);
        if (outcome.array == null || !outcome.array.isArray() || outcome.array.size() != 1 || outcome.lengthHit) {
            throw regenerationFailure();
        }
        JsonNode entry = outcome.array.get(0);
        String furigana = SubtitleText.sanitizeAiField(entry.path("furigana").asText(""));
        String translation = SubtitleText.sanitizeAiField(entry.path("translation").asText(""));
        if (!isValidFurigana(clean, furigana) || translation.isBlank()) throw regenerationFailure();
        Map<String, String> result = Map.of("furigana", furigana, "translation", translation);
        saveCache(clean, lang, result);
        return result;
    }

    private ApiException regenerationFailure() {
        return new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                "AI chưa trả về cách đọc và bản dịch hợp lệ. Nội dung đã lưu được giữ nguyên; hãy thử lại.");
    }

    public List<Map<String, String>> processJapaneseBatch(List<String> texts, String targetLanguage) {
        return processJapaneseBatch(texts, targetLanguage, true);
    }

    /**
     * @param generateFurigana when false, AI only translates; furigana = original JP text (much faster).
     */
    public List<Map<String, String>> processJapaneseBatch(List<String> texts, String targetLanguage,
                                                          boolean generateFurigana) {
        String lang = targetLanguage != null ? targetLanguage : "vi";

        List<Map<String, String>> results = new ArrayList<>();
        List<String> uncachedTexts = new ArrayList<>();
        List<Integer> uncachedIndices = new ArrayList<>();

        for (int i = 0; i < texts.size(); i++) {
            String clean = SubtitleText.normalizeJapanese(texts.get(i));
            Optional<FuriganaCache> cached = furiganaCacheRepository.findByOriginalTextAndTargetLanguage(clean, lang);
            Map<String, String> entry = new HashMap<>();
            String cachedFurigana = cached.map(FuriganaCache::getFurigana)
                    .map(SubtitleText::sanitizeAiField)
                    .orElse("");
            boolean cachedTranslationAvailable = cached.isPresent()
                    && cached.get().getTranslation() != null
                    && !cached.get().getTranslation().isBlank();
            boolean cachedFuriganaAvailable = !generateFurigana
                    || isValidFurigana(clean, cachedFurigana);
            if (cachedTranslationAvailable && cachedFuriganaAvailable) {
                entry.put("furigana", generateFurigana ? cachedFurigana : clean);
                entry.put("translation", SubtitleText.sanitizeAiField(cached.get().getTranslation()));
            } else {
                entry.put("furigana", clean);
                entry.put("translation", "");
                if (!clean.isBlank()) {
                    uncachedTexts.add(clean);
                    uncachedIndices.add(i);
                }
            }
            results.add(entry);
        }

        if (!uncachedTexts.isEmpty()) {
            processUncachedWithRecovery(uncachedTexts, uncachedIndices, lang, results, generateFurigana);
        }
        return results;
    }

    /** Used when YouTube already supplied a translation: request readings only. */
    public List<Map<String, String>> processFuriganaOnlyBatch(List<String> texts,
            List<String> translations, String language) {
        String lang = language == null ? "vi" : language;
        List<Map<String, String>> results = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<Integer> missingPositions = new ArrayList<>();
        for (int index = 0; index < texts.size(); index++) {
            String clean = SubtitleText.normalizeJapanese(texts.get(index));
            String translated = translations.get(index) == null ? "" : translations.get(index);
            Optional<FuriganaCache> cached = furiganaCacheRepository
                    .findByOriginalTextAndTargetLanguage(clean, lang);
            String furi = cached.map(FuriganaCache::getFurigana).orElse("");
            if (!isValidFurigana(clean, furi)) {
                missing.add(clean);
                missingPositions.add(index);
                furi = clean;
            }
            results.add(new HashMap<>(Map.of("furigana", furi, "translation", translated)));
        }
        if (!missing.isEmpty()) {
            List<String> generated = fetchFuriganaOnly(missing, 0);
            for (int index = 0; index < missing.size(); index++) {
                int position = missingPositions.get(index);
                String clean = missing.get(index);
                String furi = generated.get(index);
                results.get(position).put("furigana", furi);
                if (isValidFurigana(clean, furi) && !furi.equals(clean)) {
                    saveCache(clean, lang, results.get(position));
                }
            }
        }
        return results;
    }

    private List<String> fetchFuriganaOnly(List<String> texts, int depth) {
        try {
            StringBuilder input = new StringBuilder();
            for (int index = 0; index < texts.size(); index++) {
                input.append(index + 1).append(". ").append(texts.get(index)).append('\n');
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("temperature", 0);
            body.put("max_tokens", Math.min(maxTokens, 600 + texts.size() * 450));
            body.put("reasoning", Map.of("effort", "low", "exclude", true));
            body.put("messages", List.of(
                    Map.of("role", "system", "content", "Add furigana to kanji in each Japanese line. "
                            + "Return only a JSON array with exactly one object per input line in order: "
                            + "[{\"furigana\":\"full original line with 漢字(かな)\"}]. "
                            + "Do not translate, change, skip, or merge lines."),
                    Map.of("role", "user", "content", input.toString())));
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(com.jlas.common.ai.AiFeature.SHADOWING_SUBTITLES.apiKey(apiKey));
            headers.setContentType(MediaType.APPLICATION_JSON);
            String endpoint = "https://openrouter.ai/api/v1/chat/completions";
            ResponseEntity<String> response;
            try {
                response = postWithRetry(endpoint, new HttpEntity<>(body, headers), 2);
            } catch (HttpClientErrorException exception) {
                if (exception.getStatusCode().value() != 400) throw exception;
                body.remove("reasoning");
                response = postWithRetry(endpoint, new HttpEntity<>(body, headers), 2);
            }
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode array = extractResultArray(extractMessageContent(root));
            if (array != null && array.size() == texts.size()) {
                List<String> output = new ArrayList<>();
                for (int index = 0; index < texts.size(); index++) {
                    String text = texts.get(index);
                    String furi = SubtitleText.sanitizeAiField(array.get(index).path("furigana").asText(""));
                    output.add(isValidFurigana(text, furi) ? furi : text);
                }
                return output;
            }
        } catch (Exception exception) {
            System.err.println("Furigana-only batch failed: " + exception.getClass().getSimpleName());
        }
        if (texts.size() > 1 && depth < 3) {
            int half = texts.size() / 2;
            List<String> output = new ArrayList<>(fetchFuriganaOnly(texts.subList(0, half), depth + 1));
            output.addAll(fetchFuriganaOnly(texts.subList(half, texts.size()), depth + 1));
            return output;
        }
        return new ArrayList<>(texts);
    }

    private void processUncachedWithRecovery(List<String> texts, List<Integer> indices,
            String lang, List<Map<String, String>> results, boolean generateFurigana) {
        FetchOutcome outcome = fetchBatchWithJsonRetry(texts, lang, generateFurigana);

        if (outcome.array != null && outcome.array.isArray() && outcome.array.size() == texts.size()) {
            applyBatchResults(texts, indices, lang, results, outcome.array, generateFurigana, outcome.softFurigana);
            return;
        }

        // Hit max_tokens / truncated  split once (half), not into dozens of micro-batches
        if (outcome.lengthHit && texts.size() > 1) {
            System.err.println("AI length hit on " + texts.size() + " lines — split batch in half");
            splitAndRetry(texts, indices, lang, results, generateFurigana);
            return;
        }

        // Model returned fewer/more items — still apply what we can (pad missing), avoid infinite split
        if (outcome.array != null && outcome.array.isArray() && outcome.array.size() > 0
                && !outcome.lengthHit) {
            System.err.println("AI size mismatch soft-align: expected " + texts.size()
                    + " got " + outcome.array.size() + " — padding/truncating");
            applyBatchResultsAligned(texts, indices, lang, results, outcome.array, generateFurigana);
            return;
        }

        if (texts.size() == 1) {
            System.err.println("AI JSON recovery failed for single sentence, using fallback");
            applyFallback(texts.get(0), indices.get(0), results);
            return;
        }

        System.err.println("AI JSON invalid for batch of " + texts.size() + ", splitting in half...");
        splitAndRetry(texts, indices, lang, results, generateFurigana);
    }

    /** On failure only: split in half (or 1-by-1 if size==2). Not used for normal multi-pass. */
    private void splitAndRetry(List<String> texts, List<Integer> indices,
            String lang, List<Map<String, String>> results, boolean generateFurigana) {
        if (texts.size() <= 2) {
            for (int i = 0; i < texts.size(); i++) {
                processUncachedWithRecovery(
                        List.of(texts.get(i)), List.of(indices.get(i)), lang, results, generateFurigana);
            }
            return;
        }
        int mid = texts.size() / 2;
        processUncachedWithRecovery(texts.subList(0, mid), indices.subList(0, mid), lang, results, generateFurigana);
        processUncachedWithRecovery(texts.subList(mid, texts.size()), indices.subList(mid, indices.size()), lang,
                results, generateFurigana);
    }

    private record FetchOutcome(JsonNode array, boolean softFurigana, boolean lengthHit) { }

    private record CallResult(String content, boolean lengthHit) { }

    private FetchOutcome fetchBatchWithJsonRetry(List<String> texts, String lang, boolean generateFurigana) {
        JsonNode lastArray = null;
        boolean anyLengthHit = false;
        for (int attempt = 1; attempt <= jsonMaxRetries; attempt++) {
            try {
                // attempt>1: strict JSON-only prompt (DeepSeek often burns tokens on chain-of-thought)
                CallResult call = callOpenRouter(texts, lang, generateFurigana, attempt > 1);
                if (call.lengthHit) {
                    anyLengthHit = true;
                }
                String content = call.content;
                if (content == null)
                    continue;

                JsonNode array = extractResultArray(content);
                if (array != null && array.isArray()) {
                    lastArray = array;
                }
                if (array != null && array.isArray() && array.size() == texts.size()) {
                    if (!generateFurigana || isValidFuriganaBatch(texts, array)) {
                        return new FetchOutcome(array, false, false);
                    }
                    System.err.println("AI furigana invalid (attempt " + attempt
                            + "): missing 漢字(かんじ) annotations — soft-accept + dump raw");
                    dumpAiRaw(content, texts, lang, attempt, "furigana-invalid-soft");
                    return new FetchOutcome(array, true, false);
                }
                String reason = array != null && array.isArray() ? "size-mismatch" : "parse-invalid";
                if (reason.equals("parse-invalid") && content.indexOf('[') < 0) {
                    System.err.println("AI returned thinking/prose without JSON array (attempt " + attempt + ")");
                }
                if (call.lengthHit) {
                    System.err.println("AI truncated (finish_reason=length) attempt " + attempt
                            + " n=" + texts.size() + " — will multi-pass split if needed");
                }
                dumpAiRaw(content, texts, lang, attempt, reason);
                System.err.println("AI JSON parse mismatch (attempt " + attempt + "): expected "
                        + texts.size() + ", got " + (array != null && array.isArray() ? array.size() : "invalid")
                        + " — raw dumped under uploads/ai-debug/");
            } catch (Exception e) {
                System.err.println("AI batch attempt " + attempt + " error: " + e.getMessage());
            }
        }
        // Return last partial array so caller can soft-align instead of hard-fail
        return new FetchOutcome(lastArray, true, anyLengthHit);
    }

    /**
     * Save full raw AI response for debugging (user can send file back).
     */
    private void dumpAiRaw(String content, List<String> texts, String lang, int attempt, String reason) {
        try {
            Path base = Paths.get(videoUploadPath).toAbsolutePath().getParent();
            if (base == null) base = Paths.get("uploads");
            Path dir = base.resolve("ai-debug");
            Files.createDirectories(dir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String name = "ai-" + ts + "-" + DUMP_SEQ.incrementAndGet()
                    + "-a" + attempt + "-" + reason + ".txt";
            Path file = dir.resolve(name);

            StringBuilder sb = new StringBuilder();
            sb.append("=== AI DEBUG DUMP ===\n");
            sb.append("time=").append(LocalDateTime.now()).append('\n');
            sb.append("reason=").append(reason).append('\n');
            sb.append("lang=").append(lang).append('\n');
            sb.append("attempt=").append(attempt).append('\n');
            sb.append("model=").append(model).append('\n');
            sb.append("expectedCount=").append(texts.size()).append('\n');
            sb.append("\n--- INPUT SENTENCES ---\n");
            for (int i = 0; i < texts.size(); i++) {
                sb.append(i + 1).append(". ").append(texts.get(i)).append('\n');
            }
            sb.append("\n--- RAW MODEL CONTENT ---\n");
            sb.append(content == null ? "(null)" : content);
            sb.append("\n");

            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            System.err.println("[AI debug] wrote " + file.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[AI debug] dump failed: " + e.getMessage());
        }
    }

    private CallResult callOpenRouter(List<String> texts, String lang, boolean generateFurigana,
            boolean forceJsonOnly) throws Exception {
        String langLabel = "vi".equals(lang) ? "Vietnamese" : "English";

        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            numbered.append(i + 1).append(". ").append(texts.get(i)).append("\n");
        }

        String systemPrompt;
        String userPrompt;
        if (forceJsonOnly) {
            // Second attempt: kill chain-of-thought (deepseek-v4 burns max_tokens on reasoning)
            systemPrompt = """
                    You output ONLY valid JSON. No thinking. No analysis. No markdown.
                    First character of your reply MUST be '['.
                    """;
            if (generateFurigana) {
                userPrompt = """
                        Target language: %s. Exactly %d items.
                        Reply with ONLY this shape (no other text):
                        [{"furigana":"漢字(かんじ) full sentence","translation":"natural %s"}, ...]
                        Furigana = full original sentence with 漢字(かんじ) on kanji only.
                        Start with [ end with ]

                        Input:
                        %s""".formatted(langLabel, texts.size(), langLabel, numbered.toString().stripTrailing());
            } else {
                userPrompt = """
                        Target language: %s. Exactly %d items.
                        Reply with ONLY: [{"furigana":"","translation":"..."}, ...]
                        Start with [ end with ]

                        Input:
                        %s""".formatted(langLabel, texts.size(), numbered.toString().stripTrailing());
            }
        } else if (generateFurigana) {
            systemPrompt = SYSTEM_PROMPT + """

                    OUTPUT FORMAT (critical for thinking models):
                    - Your entire reply must be the JSON array and nothing else.
                    - Do NOT write step-by-step reasoning, analysis, or commentary.
                    - The first non-whitespace character MUST be '[' and the last MUST be ']'.
                    """;
            userPrompt = """
                    Target language: %s

                    For each numbered Japanese sentence below, return furigana (漢字(かんじ) on kanji only, full sentence)
                    and a natural %s translation. Return ONLY a JSON array — no thinking text.

                    %s

                    Input:

                    %s""".formatted(langLabel, langLabel, buildFewShotExamples(langLabel), numbered.toString().stripTrailing());
        } else {
            systemPrompt = """
                    You translate Japanese lines into the target language.
                    Return ONLY a JSON array. Each item MUST be:
                    {"furigana":"","translation":"..."}
                    Rules:
                    - furigana is always empty string ""
                    - ONE array element per input line — never merge lines
                    - array length MUST equal the number of numbered input lines
                    - If a line is a fragment, still translate that fragment alone
                    - No markdown, no comments, no reasoning
                    """;
            userPrompt = """
                    Target language: %s
                    There are exactly %d input lines below.
                    Return a JSON array with EXACTLY %d objects (one per line, same order).
                    Do NOT merge lines. Do NOT skip lines. Do NOT add extra objects.
                    Each object: {"furigana":"","translation":"<natural %s>"}

                    Input:

                    %s""".formatted(
                    langLabel, texts.size(), texts.size(), langLabel, numbered.toString().stripTrailing());
        }

        // Reserve for actual subtitle length instead of a fixed 2k tokens per line.
        int characters = texts.stream().mapToInt(text -> Math.min(500,
                text.codePointCount(0, text.length()))).sum();
        int completionBudget = Math.min(maxTokens, Math.max(2048, 1024 + characters * 8));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("top_p", topP);
        body.put("max_tokens", completionBudget);
        // Prefer short/no internal reasoning when the provider supports it (OpenRouter)
        body.put("reasoning", Map.of("effort", "low", "exclude", true));
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));

        String url = "https://openrouter.ai/api/v1/chat/completions";
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + com.jlas.common.ai.AiFeature.SHADOWING_SUBTITLES.apiKey(apiKey));
        headers.set("HTTP-Referer", siteUrl);
        headers.set("X-Title", "JLAS Japanese Shadowing");
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response;
        try {
            response = postWithRetry(url, entity, 4);
        } catch (HttpClientErrorException e) {
            // Some models reject "reasoning" — retry once without it
            if (e.getStatusCode().value() == 400 && body.containsKey("reasoning")) {
                body.remove("reasoning");
                entity = new HttpEntity<>(body, headers);
                response = postWithRetry(url, entity, 2);
            } else {
                throw e;
            }
        }

        if (response.getStatusCode() == HttpStatus.OK) {
            JsonNode root = objectMapper.readTree(response.getBody());
            String finish = root.path("choices").path(0).path("finish_reason").asText("");
            boolean lengthHit = "length".equalsIgnoreCase(finish);
            if (lengthHit) {
                System.err.println("AI finish_reason=length (max_tokens hit, budget=" + completionBudget
                        + ", n=" + texts.size() + ")");
            }
            return new CallResult(extractMessageContent(root), lengthHit);
        }
        return new CallResult(null, false);
    }

    /**
     * Prefer fields that actually contain a JSON array (thinking models put CoT in content).
     */
    private String extractMessageContent(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode choice = choices.get(0);
        JsonNode msg = choice.path("message");
        List<String> candidates = new ArrayList<>();
        for (String field : List.of("content", "reasoning", "reasoning_content")) {
            String text = jsonNodeToText(msg.path(field));
            if (text != null && !text.isBlank() && !"null".equalsIgnoreCase(text.trim())) {
                candidates.add(text.trim());
            }
        }
        JsonNode delta = choice.path("delta");
        if (!delta.isMissingNode()) {
            String text = jsonNodeToText(delta.path("content"));
            if (text != null && !text.isBlank() && !"null".equalsIgnoreCase(text.trim())) {
                candidates.add(text.trim());
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        // Prefer candidate that contains a JSON array
        for (String c : candidates) {
            if (c.contains("[") && c.contains("\"furigana\"")) {
                return c;
            }
        }
        for (String c : candidates) {
            if (c.contains("[")) {
                return c;
            }
        }
        return candidates.get(0);
    }

    private String jsonNodeToText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : node) {
                if (part.has("text")) {
                    sb.append(part.path("text").asText(""));
                } else if (part.isTextual()) {
                    sb.append(part.asText());
                }
            }
            return sb.toString();
        }
        return node.asText(null);
    }

    private void applyBatchResults(List<String> texts, List<Integer> indices, String lang,
            List<Map<String, String>> results, JsonNode array, boolean generateFurigana, boolean softFurigana) {
        for (int i = 0; i < texts.size(); i++) {
            String text = SubtitleText.normalizeJapanese(texts.get(i));
            Map<String, String> processed = new HashMap<>();
            String furi = SubtitleText.sanitizeAiField(array.get(i).path("furigana").asText(text));
            String trans = SubtitleText.sanitizeAiField(array.get(i).path("translation").asText(""));
            // softFurigana = batch had some invalid items — still keep per-line valid 漢字(かな)
            if (!generateFurigana) {
                processed.put("furigana", text);
            } else if (isValidFurigana(text, furi) && !furi.isBlank()) {
                processed.put("furigana", furi);
            } else {
                processed.put("furigana", text);
            }
            processed.put("translation", trans);
            results.set(indices.get(i), processed);
            if (trans != null && !trans.isBlank()) {
                saveCache(text, lang, processed);
            }
        }
    }

    /** Map shorter/longer AI arrays onto expected size (pad empty translations). */
    private void applyBatchResultsAligned(List<String> texts, List<Integer> indices, String lang,
            List<Map<String, String>> results, JsonNode array, boolean generateFurigana) {
        int n = texts.size();
        int m = array.size();
        for (int i = 0; i < n; i++) {
            String text = SubtitleText.normalizeJapanese(texts.get(i));
            Map<String, String> processed = new HashMap<>();
            processed.put("furigana", text);
            String trans = "";
            if (m == 0) {
                trans = "";
            } else if (m == n) {
                trans = SubtitleText.sanitizeAiField(array.get(i).path("translation").asText(""));
                if (generateFurigana) {
                    String furi = SubtitleText.sanitizeAiField(array.get(i).path("furigana").asText(text));
                    processed.put("furigana", isValidFurigana(text, furi) && !furi.isBlank() ? furi : text);
                }
            } else if (m < n) {
                // proportional map: reuse nearest translation for orphan fragments
                int src = Math.min(m - 1, (int) Math.round(i * (m - 1) / (double) Math.max(1, n - 1)));
                trans = SubtitleText.sanitizeAiField(array.get(src).path("translation").asText(""));
            } else {
                // extra items — take first n
                if (i < m) {
                    trans = SubtitleText.sanitizeAiField(array.get(i).path("translation").asText(""));
                }
            }
            processed.put("translation", trans);
            results.set(indices.get(i), processed);
            if (trans != null && !trans.isBlank()) {
                saveCache(text, lang, processed);
            }
        }
    }

    private String buildFewShotExamples(String langLabel) {
        if ("Vietnamese".equals(langLabel)) {
            return """
                    Example output:
                    [
                      {
                        "furigana":"今日(きょう)は天気(てんき)がいいですね。",
                        "translation":"Hôm nay thời tiết đẹp nhỉ."
                      }
                    ]

                    More examples (input  correct furigana):
                    1. 今日は天気がいいですね  今日(きょう)は天気(てんき)がいいですね
                    2. 山田さんは東京に行きます  山田(やまだ)さんは東京(とうきょう)に行(い)きます
                    3. 希望ちゃん、お元気ですか  希望(のぞみ)ちゃん、お元気(げんき)ですか

                    WRONG:
                    {"furigana":"今日は天気がいいですね","translation":"..."}
                    {"furigana":"希望(きぼう)ちゃん","translation":"..."}""";
        }
        return """
                Example output:
                [
                  {
                    "furigana":"今日(きょう)は天気(てんき)がいいですね。",
                    "translation":"Nice weather today, isn't it?"
                  }
                ]

                More examples (input  correct furigana):
                1. 今日は天気がいいですね  今日(きょう)は天気(てんき)がいいですね
                2. 山田さんは東京に行きます  山田(やまだ)さんは東京(とうきょう)に行(い)きます
                3. 希望ちゃん、お元気ですか  希望(のぞみ)ちゃん、お元気(げんき)ですか

                WRONG:
                {"furigana":"今日は天気がいいですね","translation":"..."}
                {"furigana":"希望(きぼう)ちゃん","translation":"..."}""";
    }

    private boolean isValidFuriganaBatch(List<String> originals, JsonNode array) {
        for (int i = 0; i < originals.size(); i++) {
            String original = originals.get(i);
            String furigana = array.get(i).path("furigana").asText("");
            if (!isValidFurigana(original, furigana))
                return false;
        }
        return true;
    }

    private boolean isValidFurigana(String original, String furigana) {
        if (furigana == null || furigana.isBlank()) return !containsKanji(original);
        String surface = furigana.replaceAll(
                "([\\p{IsHan}々〆ヵヶ]+)[（(][ぁ-んァ-ヶー]+[）)]", "$1");
        return original.equals(surface)
                && (!containsKanji(original) || !surface.equals(furigana));
    }

    private boolean containsKanji(String text) {
        if (text == null)
            return false;
        return text.codePoints().anyMatch(cp -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            return script == Character.UnicodeScript.HAN;
        });
    }

    private void applyFallback(String text, int resultIdx, List<Map<String, String>> results) {
        Map<String, String> fallback = new HashMap<>();
        fallback.put("furigana", text);
        fallback.put("translation", "");
        results.set(resultIdx, fallback);
    }

    /** Ghi chi phí thật của lần gọi (furigana, dịch phụ đề) để báo cáo giá tạo video Shadowing ở trang admin. */
    private void recordUsage(HttpEntity<Map<String, Object>> entity, ResponseEntity<String> response) {
        if (usageRecorder == null || response.getBody() == null) {
            return;
        }
        try {
            Object model = entity.getBody() == null ? null : entity.getBody().get("model");
            usageRecorder.recordOpenRouter(com.jlas.common.ai.AiFeature.SHADOWING_SUBTITLES, model == null ? null : String.valueOf(model),
                    response.getBody());
        } catch (Exception ignored) {
            // A body that is not JSON is handled by the caller; usage is best effort.
        }
    }

    private ResponseEntity<String> postWithRetry(String url, HttpEntity<Map<String, Object>> entity, int maxRetries) {
        int delayMs = 2000;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.postForEntity(Objects.requireNonNull(url), entity, String.class);
                recordUsage(entity, response);
                return response;
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 && attempt < maxRetries) {
                    System.err.println(
                            "OpenRouter rate limit, retry in " + delayMs + "ms (attempt " + (attempt + 1) + ")");
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    delayMs *= 2;
                } else {
                    throw e;
                }
            }
        }
        throw new RuntimeException("OpenRouter request failed after retries");
    }

    private void saveCache(String text, String lang, Map<String, String> result) {
        try {
            FuriganaCache cache = furiganaCacheRepository.findByOriginalTextAndTargetLanguage(text, lang)
                    .orElseGet(FuriganaCache::new);
            cache.setTextHash(sha256(text + ":" + lang));
            cache.setOriginalText(text);
            cache.setFurigana(result.get("furigana"));
            cache.setTranslation(result.get("translation"));
            cache.setTargetLanguage(lang);
            furiganaCacheRepository.save(cache);
        } catch (Exception ignored) {
        }
    }

    private String sha256(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private JsonNode extractResultArray(String content) {
        if (content == null || content.isBlank() || "null".equalsIgnoreCase(content.trim()))
            return null;
        String trimmed = content.trim();

        // Strip markdown code fences if model ignores instructions
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
        }

        try {
            if (trimmed.contains("[")) {
                int start = trimmed.indexOf('[');
                int end = trimmed.lastIndexOf(']');
                String arr = end > start ? trimmed.substring(start, end + 1) : trimmed.substring(start);
                JsonNode parsed = tryParseJsonArray(arr);
                if (parsed != null)
                    return parsed;
            }
            JsonNode node = objectMapper.readTree(repairJson(trimmed));
            if (node.isArray())
                return node;
            for (String key : List.of("items", "lines", "results", "data", "sentences")) {
                if (node.has(key) && node.get(key).isArray())
                    return node.get(key);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private JsonNode tryParseJsonArray(String arr) {
        for (String candidate : List.of(arr, repairJson(arr), salvagePartialArray(arr))) {
            if (candidate == null || candidate.isBlank())
                continue;
            try {
                JsonNode node = objectMapper.readTree(candidate);
                if (node != null && node.isArray() && node.size() > 0)
                    return node;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** Remove trailing commas that small/medium models often emit. */
    private String repairJson(String json) {
        if (json == null)
            return null;
        return json
                .replaceAll(",\\s*}", "}")
                .replaceAll(",\\s*]", "]");
    }

    /**
     * When max_tokens cuts mid-object, keep complete {...} objects so soft-align can use them.
     */
    private String salvagePartialArray(String arr) {
        if (arr == null || !arr.contains("{"))
            return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        int lastCompleteEnd = -1;
        for (int i = 0; i < arr.length(); i++) {
            char c = arr.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    lastCompleteEnd = i;
                }
            }
        }
        if (lastCompleteEnd < 0)
            return null;
        StringBuilder sb = new StringBuilder();
        sb.append(arr, 0, lastCompleteEnd + 1);
        // drop trailing comma after last object
        while (sb.length() > 0) {
            char last = sb.charAt(sb.length() - 1);
            if (last == ',' || Character.isWhitespace(last)) {
                sb.setLength(sb.length() - 1);
            } else {
                break;
            }
        }
        if (sb.charAt(0) != '[') {
            sb.insert(0, '[');
        }
        if (sb.charAt(sb.length() - 1) != ']') {
            sb.append(']');
        }
        return repairJson(sb.toString());
    }
}
