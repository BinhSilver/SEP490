package com.jlas.moderation.service;

import com.jlas.moderation.dto.ModerationResult;
import com.jlas.moderation.dto.TextHighlight;
import com.jlas.moderation.model.ContentModerationLog;
import com.jlas.moderation.model.ModerationReviewQueue;
import com.jlas.moderation.model.ModerationVerdict;
import com.jlas.moderation.repository.ContentModerationLogRepository;
import com.jlas.moderation.repository.ModerationReviewQueueRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kiểm duyệt chữ (denylist) + ảnh (OpenRouter vision khi cấu hình model).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContentModerationService {

    private final ContentModerationLogRepository logRepository;
    private final ModerationReviewQueueRepository queueRepository;
    private final ObjectMapper objectMapper;
    private final OpenRouterVisionClient visionClient;
    private final DenylistService denylist;

    @Value("${moderation.enabled:true}")
    private boolean enabled;

    @Value("${moderation.image.enabled:true}")
    private boolean imageEnabled;

    private static final Pattern URL_SPAM = Pattern.compile(
            "(?i)(https?://|www\\.)\\S{8,}", Pattern.UNICODE_CASE);
    private static final Pattern MANY_URLS = Pattern.compile("(?i)(https?://|www\\.)");

    public record DenylistMatch(String term, int start, int end) {}

    /** Lightweight deterministic check used by the editor while the user types. */
    public List<DenylistMatch> findDenylistMatches(String text) {
        if (!StringUtils.hasText(text)) return List.of();
        String lower = text.toLowerCase(Locale.ROOT);
        DenylistService.Snapshot terms = denylist.current();
        List<DenylistMatch> matches = new ArrayList<>();
        addLiteralMatches(text, lower, terms.accentSensitiveBlock(), matches);
        addLiteralMatches(text, lower, terms.block(), matches);
        return matches;
    }

    public record DenylistHit(String term, String action) {}

    /**
     * Admin "try a text" tool: every active term that matches, using exactly the same
     * matching rules as {@link #scanText(String)} but without caching or logging.
     */
    public List<DenylistHit> testDenylist(String text) {
        if (!StringUtils.hasText(text)) return List.of();
        String canonical = canonicalForMatch(text);
        String normalized = normalize(text);
        DenylistService.Snapshot terms = denylist.current();
        List<DenylistHit> hits = new ArrayList<>();
        for (String term : terms.accentSensitiveBlock()) {
            if (containsBounded(canonical, canonicalForMatch(term), false)) {
                hits.add(new DenylistHit(term, DenylistService.BLOCK));
            }
        }
        for (String term : terms.block()) {
            if (matchesTerm(normalized, term)) hits.add(new DenylistHit(term, DenylistService.BLOCK));
        }
        for (String term : terms.review()) {
            if (matchesTerm(normalized, term)) hits.add(new DenylistHit(term, DenylistService.REVIEW));
        }
        return hits;
    }

    /**
     * Positions of every term reported by {@link #testDenylist(String)} in the original text.
     * Matching folds case and Vietnamese accents one character at a time, so indexes stay aligned
     * with the original string. Obfuscated spellings (e.g. "p o r n") are still listed by
     * testDenylist but cannot be located, so they get no span.
     */
    public List<TextHighlight> highlight(String text) {
        if (!StringUtils.hasText(text)) return List.of();
        String lower = text.toLowerCase(Locale.ROOT);
        String folded = foldPerChar(text);
        List<TextHighlight> spans = new ArrayList<>();
        Set<String> accentSensitive = Set.copyOf(denylist.current().accentSensitiveBlock());
        for (DenylistHit hit : testDenylist(text)) {
            boolean exact = accentSensitive.contains(hit.term());
            String haystack = exact ? lower : folded;
            String needle = exact ? hit.term().toLowerCase(Locale.ROOT) : foldPerChar(hit.term());
            if (needle.isEmpty() || needle.length() > haystack.length()) continue;
            boolean bounded = !containsJapanese(needle);
            int from = 0;
            while ((from = haystack.indexOf(needle, from)) >= 0) {
                int end = from + needle.length();
                boolean ok = !bounded || ((from == 0 || !Character.isLetterOrDigit(haystack.charAt(from - 1)))
                        && (end == haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end))));
                if (ok) spans.add(new TextHighlight(from, end, hit.term(), hit.action()));
                from = end;
            }
        }
        spans.sort(Comparator.comparingInt(TextHighlight::start)
                .thenComparing(Comparator.comparingInt(TextHighlight::end).reversed()));
        List<TextHighlight> merged = new ArrayList<>();
        int lastEnd = -1;
        for (TextHighlight span : spans) {
            if (span.start() >= lastEnd) {
                merged.add(span);
                lastEnd = span.end();
            }
        }
        return merged;
    }

    /** Lowercases and strips accents per character; output length always equals input length. */
    private static String foldPerChar(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (c == 'đ') {
                out.append('d');
                continue;
            }
            String base = java.text.Normalizer.normalize(String.valueOf(c), java.text.Normalizer.Form.NFKD)
                    .replaceAll("\\p{M}", "");
            out.append(base.length() == 1 ? Character.toLowerCase(base.charAt(0)) : c);
        }
        return out.toString();
    }

    private static void addLiteralMatches(String text, String lower, List<String> terms,
                                          List<DenylistMatch> matches) {
        for (String configured : terms) {
            String term = configured.trim();
            if (term.isEmpty()) continue;
            int from = 0;
            String needle = term.toLowerCase(Locale.ROOT);
            while ((from = lower.indexOf(needle, from)) >= 0) {
                matches.add(new DenylistMatch(text.substring(from, from + needle.length()), from,
                        from + needle.length()));
                from += Math.max(1, needle.length());
            }
        }
    }

    public ModerationResult scanText(String text) {
        if (!enabled) {
            return safeResult("Moderation disabled");
        }
        if (!StringUtils.hasText(text)) {
            return safeResult("Empty text");
        }
        String canonical = canonicalForMatch(text);
        String normalized = normalize(text);
        DenylistService.Snapshot terms = denylist.current();
        // The denylist version is part of the key so cached verdicts expire when terms change.
        String hash = sha256("text-v2|" + terms.version() + "|" + canonical);

        // Cache hit (cùng nội dung đã chuẩn hóa chống lách)
        return logRepository.findFirstByContentHashOrderByCreatedAtDesc(hash)
                .filter(l -> l.getVerdict() != null && l.getVerdict() != ModerationVerdict.ERROR)
                .map(l -> ModerationResult.builder()
                        .verdict(l.getVerdict())
                        .categories(parseCategories(l.getCategoriesJson()))
                        .confidence(l.getConfidence() != null ? l.getConfidence() : 0.9)
                        .summary(l.getSummary())
                        .modelName(l.getModelName() != null ? l.getModelName() : "keyword-cache")
                        .build())
                .orElseGet(() -> runScan(terms, canonical, normalized, hash));
    }

    public ModerationResult scanCombined(List<String> parts) {
        if (parts == null || parts.isEmpty()) {
            return safeResult("Empty batch");
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (StringUtils.hasText(p)) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(p.trim());
            }
        }
        // Giới hạn độ dài để denylist vẫn hiệu quả
        String joined = sb.length() > 12000 ? sb.substring(0, 12000) : sb.toString();
        return scanText(joined);
    }

    /** Chỉ lọc tên file (không gọi vision). */
    public ModerationResult scanImagePlaceholder(String filenameOrUrl) {
        if (!enabled) {
            return safeResult("Moderation disabled");
        }
        if (filenameOrUrl != null) {
            String lower = filenameOrUrl.toLowerCase(Locale.ROOT);
            for (String t : denylist.current().block()) {
                if (lower.contains(t)) {
                    return ModerationResult.builder()
                            .verdict(ModerationVerdict.BLOCKED)
                            .categories(List.of("NSFW_FILENAME"))
                            .confidence(0.85)
                            .summary("Tên file ảnh có dấu hiệu nội dung không phù hợp.")
                            .modelName("filename-filter")
                            .build();
                }
            }
        }
        return safeResult("Filename OK");
    }

    /**
     * Ảnh: filename filter  OpenRouter vision nếu {@code moderation.image.model} đã set.
     * Model trống: SAFE + note (dev chưa cấu hình).
     */
    public ModerationResult scanImage(java.nio.file.Path imagePath, String originalFilename) {
        if (!enabled || !imageEnabled) {
            return safeResult("Image moderation disabled");
        }
        ModerationResult nameCheck = scanImagePlaceholder(
                originalFilename != null ? originalFilename
                        : (imagePath != null ? imagePath.getFileName().toString() : null));
        if (nameCheck.isBlocked()) {
            return nameCheck;
        }
        if (imagePath == null || !java.nio.file.Files.isRegularFile(imagePath)) {
            return ModerationResult.builder()
                    .verdict(ModerationVerdict.ERROR)
                    .categories(List.of("IO"))
                    .confidence(0)
                    .summary("Không đọc được file ảnh")
                    .modelName("local")
                    .build();
        }
        if (!visionClient.isConfigured()) {
            return ModerationResult.builder()
                    .verdict(ModerationVerdict.SAFE)
                    .categories(List.of())
                    .confidence(0.5)
                    .summary("Vision model chưa cấu hình (moderation.image.model) — bỏ qua deep-scan.")
                    .modelName("vision-skipped")
                    .build();
        }
        try {
            String imageHash = sha256Bytes(java.nio.file.Files.readAllBytes(imagePath));
            OptionalCache hit = findCachedVerdict(imageHash);
            if (hit != null) {
                return hit.result();
            }
            String raw = visionClient.analyzeImage(imagePath,
                    "Classify this image for a Japanese learning app community. JSON only as specified.");
            ModerationResult result = parseVisionJson(raw, visionClient.getPrimaryModel());
            // Cache vision result by image bytes hash
            try {
                logRepository.save(ContentModerationLog.builder()
                        .targetType("IMAGE_BYTES")
                        .contentHash(imageHash)
                        .source("VISION")
                        .verdict(result.getVerdict())
                        .categoriesJson(toJson(result.getCategories()))
                        .confidence(result.getConfidence())
                        .modelName(result.getModelName())
                        .summary(result.getSummary())
                        .build());
            } catch (Exception e) {
                log.debug("Vision cache save skip: {}", e.getMessage());
            }
            return result;
        } catch (Exception e) {
            log.warn("Vision scan failed: {}", e.getMessage());
            return ModerationResult.builder()
                    .verdict(ModerationVerdict.ERROR)
                    .categories(List.of("VISION_ERROR"))
                    .confidence(0)
                    .summary("Vision lỗi: " + e.getMessage())
                    .modelName(visionClient.getPrimaryModel())
                    .build();
        }
    }

    private record OptionalCache(ModerationResult result) {}

    private OptionalCache findCachedVerdict(String contentHash) {
        return logRepository.findFirstByContentHashOrderByCreatedAtDesc(contentHash)
                .filter(l -> l.getVerdict() != null && l.getVerdict() != ModerationVerdict.ERROR)
                .map(l -> new OptionalCache(ModerationResult.builder()
                        .verdict(l.getVerdict())
                        .categories(parseCategories(l.getCategoriesJson()))
                        .confidence(l.getConfidence() != null ? l.getConfidence() : 0.9)
                        .summary((l.getSummary() != null ? l.getSummary() : "") + " [cache]")
                        .modelName(l.getModelName() != null ? l.getModelName() + "+cache" : "cache")
                        .build()))
                .orElse(null);
    }

    /** Gộp nhiều kết quả: BLOCK > REVIEW > ERROR > SAFE. */
    public ModerationResult mergeVerdicts(List<ModerationResult> results) {
        if (results == null || results.isEmpty()) {
            return safeResult("empty merge");
        }
        ModerationVerdict worst = ModerationVerdict.SAFE;
        Set<String> cats = new LinkedHashSet<>();
        Set<String> summaries = new LinkedHashSet<>();
        double conf = 1.0;
        String model = "merge";
        for (ModerationResult r : results) {
            if (r == null || r.getVerdict() == null) continue;
            worst = worse(worst, r.getVerdict());
            if (r.getCategories() != null) cats.addAll(r.getCategories());
            if (StringUtils.hasText(r.getSummary())) {
                summaries.add(r.getSummary().trim());
            }
            conf = Math.min(conf, r.getConfidence());
            if (r.getModelName() != null) model = r.getModelName();
        }
        String summary = String.join(" | ", summaries);
        if (summary.length() > 400) summary = summary.substring(0, 397) + "…";
        return ModerationResult.builder()
                .verdict(worst)
                .categories(List.copyOf(cats))
                .confidence(conf)
                .summary(!summary.isEmpty() ? summary : worst.name())
                .modelName(model)
                .build();
    }

    public static ModerationVerdict worse(ModerationVerdict a, ModerationVerdict b) {
        return rank(b) > rank(a) ? b : a;
    }

    private static int rank(ModerationVerdict v) {
        if (v == null) return 0;
        return switch (v) {
            case SAFE, NOT_SCANNED -> 1;
            case ERROR -> 2;
            case REVIEW -> 3;
            case BLOCKED -> 4;
        };
    }

    private ModerationResult parseVisionJson(String raw, String modelName) {
        try {
            String json = raw;
            int i = raw.indexOf('{');
            int j = raw.lastIndexOf('}');
            if (i >= 0 && j > i) {
                json = raw.substring(i, j + 1);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            String v = String.valueOf(map.getOrDefault("verdict", "REVIEW")).toUpperCase(Locale.ROOT);
            ModerationVerdict verdict;
            try {
                verdict = ModerationVerdict.valueOf(v);
            } catch (Exception e) {
                verdict = ModerationVerdict.REVIEW;
            }
            if (verdict == ModerationVerdict.NOT_SCANNED) {
                verdict = ModerationVerdict.REVIEW;
            }
            List<String> cats = new ArrayList<>();
            Object c = map.get("categories");
            if (c instanceof List<?> list) {
                for (Object o : list) cats.add(String.valueOf(o));
            }
            double conf = 0.7;
            if (map.get("confidence") instanceof Number n) {
                conf = n.doubleValue();
            }
            String summary = map.get("summary") != null ? String.valueOf(map.get("summary")) : verdict.name();
            if (map.get("risk") instanceof Number risk) {
                summary = summary + " (risk=" + risk.intValue() + ")";
            }
            if (map.get("reason") != null && !String.valueOf(map.get("reason")).isBlank()) {
                summary = summary + " | " + map.get("reason");
            }
            if (summary.length() > 480) {
                summary = summary.substring(0, 480);
            }
            return ModerationResult.builder()
                    .verdict(verdict)
                    .categories(cats)
                    .confidence(conf)
                    .summary(summary)
                    .modelName(modelName)
                    .build();
        } catch (Exception e) {
            log.warn("Parse vision JSON failed: {}", e.getMessage());
            return ModerationResult.builder()
                    .verdict(ModerationVerdict.REVIEW)
                    .categories(List.of("PARSE_ERROR"))
                    .confidence(0.4)
                    .summary("Không parse được JSON vision — chuyển REVIEW")
                    .modelName(modelName)
                    .build();
        }
    }

    @Transactional
    public ContentModerationLog persistLog(
            String targetType,
            Long targetId,
            Long ownerUserId,
            String source,
            String rawText,
            ModerationResult result
    ) {
        String hash = sha256("text-v2|" + canonicalForMatch(rawText != null ? rawText : ""));
        ContentModerationLog logEntity = ContentModerationLog.builder()
                .targetType(targetType)
                .targetId(targetId)
                .ownerUserId(ownerUserId)
                .contentHash(hash)
                .source(source)
                .verdict(result.getVerdict())
                .categoriesJson(toJson(result.getCategories()))
                .confidence(result.getConfidence())
                .modelName(result.getModelName())
                .summary(result.getSummary())
                .build();
        return logRepository.save(logEntity);
    }

    @Transactional
    public void enqueueReview(
            ContentModerationLog logEntity,
            String targetType,
            Long targetId,
            Long ownerUserId,
            String titleSnapshot,
            String summary
    ) {
        if (targetId == null) {
            return;
        }
        // Tránh spam queue
        if (queueRepository.findFirstByTargetTypeAndTargetIdAndStatus(targetType, targetId, "OPEN").isPresent()) {
            return;
        }
        queueRepository.save(ModerationReviewQueue.builder()
                .logId(logEntity != null ? logEntity.getId() : null)
                .targetType(targetType)
                .targetId(targetId)
                .ownerUserId(ownerUserId)
                .titleSnapshot(titleSnapshot)
                .summary(summary)
                .status("OPEN")
                .build());
    }

    private ModerationResult runScan(DenylistService.Snapshot terms, String canonical, String normalized,
                                     String hash) {
        List<String> cats = new ArrayList<>();

        for (String term : terms.accentSensitiveBlock()) {
            if (containsBounded(canonical, canonicalForMatch(term), false)) {
                return blockedKeywordResult(hash, cats);
            }
        }

        for (String term : terms.block()) {
            if (matchesTerm(normalized, term)) {
                return blockedKeywordResult(hash, cats);
            }
        }

        for (String term : terms.review()) {
            if (matchesTerm(normalized, term)) {
                cats.add("SENSITIVE_TOPIC");
                return cacheAndReturn(hash, ModerationResult.builder()
                        .verdict(ModerationVerdict.REVIEW)
                        .categories(cats)
                        .confidence(0.75)
                        .summary("Nội dung nhạy cảm — cần admin duyệt trước khi công khai.")
                        .modelName("keyword-reviewlist")
                        .build());
            }
        }

        // Spam: quá nhiều URL
        var m = MANY_URLS.matcher(normalized);
        int urlCount = 0;
        while (m.find()) urlCount++;
        if (urlCount >= 4 || (urlCount >= 2 && normalized.length() < 80)) {
            return cacheAndReturn(hash, ModerationResult.builder()
                    .verdict(ModerationVerdict.REVIEW)
                    .categories(List.of("SPAM"))
                    .confidence(0.7)
                    .summary("Phát hiện nhiều liên kết — cần duyệt thủ công.")
                    .modelName("url-heuristic")
                    .build());
        }

        return cacheAndReturn(hash, safeResult("OK"));
    }

    private ModerationResult blockedKeywordResult(String hash, List<String> cats) {
        cats.add("TOXIC_OR_NSFW");
        return cacheAndReturn(hash, ModerationResult.builder()
                .verdict(ModerationVerdict.BLOCKED)
                .categories(cats)
                .confidence(0.95)
                .summary("Nội dung không phù hợp chính sách cộng đồng JLAS.")
                .modelName("keyword-blocklist")
                .build());
    }

    /** Match theo ranh giới từ; kana/kanji vẫn cho phép nằm trong một cụm dài. */
    private static boolean matchesTerm(String normalized, String term) {
        if (!StringUtils.hasText(term)) return false;
        String nt = normalize(term);
        if (!StringUtils.hasText(nt)) return false;
        if (containsJapanese(nt)) return normalized.contains(nt);

        boolean prefixTerm = "porn".equals(nt) || "pedophil".equals(nt);
        if (containsBounded(normalized, nt, prefixTerm)) return true;
        return matchesSeparatedToken(normalized, nt);
    }

    private static boolean containsBounded(String text, String term, boolean allowWordSuffix) {
        int from = 0;
        while ((from = text.indexOf(term, from)) >= 0) {
            int end = from + term.length();
            boolean beforeBoundary = from == 0 || !Character.isLetterOrDigit(text.codePointBefore(from));
            boolean afterBoundary = allowWordSuffix || end == text.length()
                    || !Character.isLetterOrDigit(text.codePointAt(end));
            if (beforeBoundary && afterBoundary) return true;
            from = Math.max(from + 1, end);
        }
        return false;
    }

    private static boolean containsJapanese(String text) {
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
                || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HIRAGANA
                || Character.UnicodeScript.of(cp) == Character.UnicodeScript.KATAKANA);
    }

    /** Bắt dạng cố tình chèn dấu/khoảng trắng như p.o.r.n, nhưng không nối các từ bình thường. */
    private static boolean matchesSeparatedToken(String text, String term) {
        if (term.indexOf(' ') >= 0 || term.codePointCount(0, term.length()) < 3) return false;
        StringBuilder regex = new StringBuilder("(?<![\\p{L}\\p{N}])");
        int[] codePoints = term.codePoints().toArray();
        for (int i = 0; i < codePoints.length; i++) {
            regex.append(Pattern.quote(new String(Character.toChars(codePoints[i]))));
            if (i + 1 < codePoints.length) regex.append("[\\s\\p{Punct}_]*");
        }
        regex.append("(?![\\p{L}\\p{N}])");
        return Pattern.compile(regex.toString(), Pattern.UNICODE_CASE).matcher(text).find();
    }

    private ModerationResult cacheAndReturn(String hash, ModerationResult r) {
        try {
            logRepository.save(ContentModerationLog.builder()
                    .targetType("TEXT_SNIPPET")
                    .contentHash(hash)
                    .source("PRESCAN")
                    .verdict(r.getVerdict())
                    .categoriesJson(toJson(r.getCategories()))
                    .confidence(r.getConfidence())
                    .modelName(r.getModelName())
                    .summary(r.getSummary())
                    .build());
        } catch (Exception e) {
            log.debug("Cache moderation skip: {}", e.getMessage());
        }
        return r;
    }

    private static ModerationResult safeResult(String summary) {
        return ModerationResult.builder()
                .verdict(ModerationVerdict.SAFE)
                .categories(List.of())
                .confidence(1.0)
                .summary(summary)
                .modelName("keyword")
                .build();
    }

    /** Giữ dấu để cache và các từ tiếng Việt ngắn không bị nhập nhằng. */
    private static String canonicalForMatch(String text) {
        if (text == null) return "";
        String t = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\u200B-\\u200F\\u202A-\\u202E\\uFEFF]", "");
        t = mapConfusables(t);
        return t.replace('　', ' ').replace('\n', ' ').replace('\r', ' ')
                .replaceAll("\\s+", " ").trim();
    }

    /**
     * Chuẩn hóa chống lách cơ bản cho các cụm đủ dài:
     * NFKC, lower, bỏ dấu VI, confusable CyrillicLatin, zero-width, leet nhẹ.
     */
    private static String normalize(String text) {
        if (text == null) return "";
        String t = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);
        t = t.toLowerCase(Locale.ROOT);
        t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        // zero-width / bidi controls
        t = t.replaceAll("[\\u200B-\\u200F\\u202A-\\u202E\\uFEFF]", "");
        t = mapConfusables(t);
        // leet cơ bản
        t = t.replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('4', 'a')
                .replace('5', 's').replace('7', 't').replace('@', 'a').replace('$', 's');
        t = t.replace('　', ' ').replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return t;
    }

    /** Một số Cyrillic/Greek trông giống Latin. */
    private static String mapConfusables(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            sb.appendCodePoint(CONFUSABLE.getOrDefault(cp, cp));
        }
        return sb.toString();
    }

    private static final Map<Integer, Integer> CONFUSABLE = Map.ofEntries(
            Map.entry(0x0430, (int) 'a'), // а
            Map.entry(0x0435, (int) 'e'), // е
            Map.entry(0x043E, (int) 'o'), // о
            Map.entry(0x0440, (int) 'p'), // р
            Map.entry(0x0441, (int) 'c'), // с
            Map.entry(0x0443, (int) 'y'), // у
            Map.entry(0x0445, (int) 'x'), // х
            Map.entry(0x0456, (int) 'i'), // і
            Map.entry(0x04BB, (int) 'h'), // һ
            Map.entry(0x0391, (int) 'a'), // Α
            Map.entry(0x0392, (int) 'b'),
            Map.entry(0x0395, (int) 'e'),
            Map.entry(0x039F, (int) 'o'),
            Map.entry(0x03A1, (int) 'p'),
            Map.entry(0x03A7, (int) 'x'),
            Map.entry(0x03B1, (int) 'a'),
            Map.entry(0x03BF, (int) 'o'),
            Map.entry(0x03C1, (int) 'p')
    );

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String sha256Bytes(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            return Integer.toHexString(java.util.Arrays.hashCode(data));
        }
    }

    private String toJson(List<String> categories) {
        try {
            return objectMapper.writeValueAsString(categories != null ? categories : List.of());
        } catch (Exception e) {
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseCategories(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
