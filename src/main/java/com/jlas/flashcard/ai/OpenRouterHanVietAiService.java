package com.jlas.flashcard.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real Hán Việt generation via OpenRouter.
 * Returns Sino-Vietnamese readings (âm Hán Việt), e.g. 台風  Đài phong, 位置  Vị trí.
 */
@Service
@Primary
@RequiredArgsConstructor
@Slf4j
public class OpenRouterHanVietAiService implements HanVietAiService {

    private static final Pattern LINE = Pattern.compile(
            "^\\s*(.+?)\\s*-\\s*(.+?)\\s*:\\s*(.+?)(?:\\s*\\(([^)]*)\\))?\\s*$"
    );

    private static final String SYS_SINGLE = """
            Bạn là chuyên gia Hán Việt (Sino-Vietnamese readings) cho người học tiếng Nhật.
            Nhiệm vụ: trả về CHỈ chuỗi âm Hán Việt của từ kanji, viết hoa chữ cái đầu mỗi tiếng, cách nhau bằng dấu cách.
            Ví dụ:
            - 台風  Đài phong
            - 位置  Vị trí
            - 景色  Cảnh sắc
            - 加えて  Gia (chỉ phần kanji 加 nếu từ có hiragana)
            Quy tắc:
            - KHÔNG trả "AI-..." hay lặp lại kanji
            - KHÔNG giải thích, KHÔNG JSON, KHÔNG markdown
            - Chỉ trả âm Hán Việt thuần (1-4 tiếng Việt)
            - Nếu không chắc, suy từ từng chữ kanji phổ biến
            """;

    private static final String SYS_BATCH = """
            Bạn là chuyên gia Hán Việt. Với mỗi mục, trả âm Hán Việt (Sino-Vietnamese).
            Trả ĐÚNG JSON array, không markdown:
            [{"id":1,"hanViet":"Đài phong"},{"id":2,"hanViet":"Vị trí"}]
            Quy tắc: chỉ âm Hán Việt, viết hoa chữ cái đầu; không lặp kanji; không prefix AI-.
            """;

    private static final String SYS_ENRICH = """
            Bạn là từ điển tiếng Nhật cho người Việt.
            Input: MỘT từ/cụm kanji (key bắt buộc).
            Trả ĐÚNG JSON thuần (không markdown):
            {"kanji":"...","reading":"hiragana","meaning":"nghĩa tiếng Việt","hanViet":"Âm Hán Việt"}
            Ví dụ:
            {"kanji":"台風","reading":"たいふう","meaning":"Cơn bão","hanViet":"Đài phong"}
            {"kanji":"位置","reading":"いち","meaning":"Vị trí","hanViet":"Vị trí"}
            {"kanji":"加えて","reading":"くわえて","meaning":"Thêm vào đó, ngoài ra","hanViet":"Gia"}
            Quy tắc:
            - reading: hiragana (hoặc katakana nếu cần)
            - meaning: tiếng Việt ngắn gọn
            - hanViet: âm Hán Việt, viết hoa chữ cái đầu, không prefix AI-
            - Không giải thích thêm ngoài JSON
            """;

    private static final String SYS_ENRICH_BATCH = """
            Bạn là từ điển tiếng Nhật cho người Việt.
            Input: Danh sách các từ Kanji với id.
            Trả ĐÚNG JSON array thuần (không markdown):
            [
              {"id":0,"kanji":"台風","reading":"たいふう","meaning":"Cơn bão","hanViet":"Đài phong"},
              {"id":1,"kanji":"位置","reading":"いち","meaning":"Vị trí","hanViet":"Vị trí"}
            ]
            Quy tắc:
            - reading: hiragana (hoặc katakana nếu cần)
            - meaning: tiếng Việt ngắn gọn
            - hanViet: âm Hán Việt, viết hoa chữ cái đầu, không prefix AI-
            - Giữ đúng id nguyên dạng của từng từ
            - Không giải thích thêm ngoài JSON
            """;

    @Value("${openrouter.api.key:}")
    private String apiKey;

    @Value("${openrouter.api.url:https://openrouter.ai/api/v1/chat/completions}")
    private String apiUrl;

    @Value("${openrouter.model:qwen/qwen-2.5-7b-instruct}")
    private String model;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String generateHanViet(String front, String back) {
        if (front == null || front.isBlank()) {
            return "";
        }
        String fallback = heuristicHanViet(front.trim());
        if (!hasValidKey()) {
            return fallback;
        }
        String user = "Kanji/từ: " + front.trim()
                + (back != null && !back.isBlank() ? "\nNghĩa/context: " + back.trim() : "")
                + "\nChỉ trả âm Hán Việt:";
        String raw = callOpenRouter(SYS_SINGLE, user, 80);
        String cleaned = cleanHanViet(raw, front);
        return cleaned.isBlank() ? fallback : cleaned;
    }

    @Override
    public CardEnrichResult enrichFromKanji(String kanji) {
        if (kanji == null || kanji.isBlank()) {
            throw new IllegalArgumentException("Kanji is required (key). Meaning or Hán Việt alone is not supported.");
        }
        String k = kanji.trim();
        CardEnrichResult fallback = heuristicEnrich(k);

        if (!hasValidKey()) {
            return fallback;
        }
        String raw = callOpenRouter(SYS_ENRICH, "Kanji: " + k + "\nTrả JSON reading, meaning, hanViet:", 200);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            String clean = stripMarkdown(raw);
            JsonNode n = objectMapper.readTree(clean);
            String reading = textOr(n, "reading", fallback.reading());
            String meaning = textOr(n, "meaning", fallback.meaning());
            String hv = cleanHanViet(textOr(n, "hanViet", fallback.hanViet()), k);
            if (hv.isBlank()) {
                hv = fallback.hanViet();
            }
            if (meaning == null || meaning.isBlank()) {
                meaning = fallback.meaning();
            }
            if (reading == null || reading.isBlank()) {
                reading = fallback.reading();
            }
            String back = buildBack(reading, meaning);
            return new CardEnrichResult(k, reading, meaning, hv, back);
        } catch (Exception e) {
            log.warn("enrichFromKanji parse failed: {}", e.getMessage());
            return fallback;
        }
    }

    @Override
    public List<CardEnrichResult> enrichBatchFromKanji(List<String> kanjiList) {
        List<CardEnrichResult> results = new ArrayList<>();
        if (kanjiList == null || kanjiList.isEmpty()) {
            return results;
        }

        if (!hasValidKey()) {
            for (String k : kanjiList) {
                results.add(heuristicEnrich(k));
            }
            return results;
        }

        StringBuilder user = new StringBuilder("Danh sách Kanji:\n");
        for (int i = 0; i < kanjiList.size(); i++) {
            String k = kanjiList.get(i);
            if (k != null && !k.isBlank()) {
                user.append("id=").append(i).append(" | kanji=").append(k.trim()).append('\n');
            }
        }
        user.append("\nTrả JSON array:");

        int maxTokens = Math.min(4000, Math.max(500, kanjiList.size() * 150));
        String raw = callOpenRouter(SYS_ENRICH_BATCH, user.toString(), maxTokens);
        Map<Integer, CardEnrichResult> parsedMap = new HashMap<>();
        if (raw != null && !raw.isBlank()) {
            try {
                String clean = stripMarkdown(raw);
                JsonNode arr = objectMapper.readTree(clean);
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        int id = n.path("id").asInt(-1);
                        String originalK = id >= 0 && id < kanjiList.size() ? kanjiList.get(id) : "";
                        String returnedK = textOr(n, "kanji", originalK);
                        if (!sameKanji(returnedK, originalK)) {
                            log.warn("Ignoring batch AI item {} because returned kanji '{}' differs from requested '{}'",
                                    id, returnedK, originalK);
                            continue;
                        }
                        // The request's Kanji is the immutable key. AI may only
                        // enrich its reading, meaning and Hán Việt.
                        String k = originalK;

                        String reading = textOr(n, "reading", "");
                        String meaning = textOr(n, "meaning", "");
                        String hv = cleanHanViet(textOr(n, "hanViet", ""), k);
                        String back = buildBack(reading, meaning);
                        if (id >= 0) {
                            parsedMap.put(id, new CardEnrichResult(k, reading, meaning, hv, back));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Batch enrich parse failed: {}", e.getMessage());
            }
        }

        for (int i = 0; i < kanjiList.size(); i++) {
            String k = kanjiList.get(i);
            if (parsedMap.containsKey(i)) {
                results.add(parsedMap.get(i));
            } else {
                results.add(heuristicEnrich(k));
            }
        }

        return results;
    }

    @Override
    public Map<Long, String> generateHanVietBatch(List<HanVietRequest> items) {
        Map<Long, String> out = new HashMap<>();
        if (items == null || items.isEmpty()) {
            return out;
        }
        if (!hasValidKey() || items.size() == 1) {
            for (HanVietRequest item : items) {
                if (item != null && item.id() != null) {
                    out.put(item.id(), generateHanViet(item.front(), item.back()));
                }
            }
            return out;
        }

        StringBuilder user = new StringBuilder("Danh sách:\n");
        for (HanVietRequest item : items) {
            if (item == null || item.id() == null) continue;
            user.append("id=").append(item.id())
                    .append(" | kanji=").append(nullToEmpty(item.front()))
                    .append(" | nghĩa=").append(nullToEmpty(item.back()))
                    .append('\n');
        }
        user.append("Trả JSON array id + hanViet.");

        String raw = callOpenRouter(SYS_BATCH, user.toString(), 800);
        try {
            String clean = stripMarkdown(raw);
            JsonNode arr = objectMapper.readTree(clean);
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    long id = n.path("id").asLong(0);
                    String hv = cleanHanViet(n.path("hanViet").asText(""), null);
                    if (id > 0 && !hv.isBlank()) {
                        out.put(id, hv);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Batch Hán Việt parse failed: {}", e.getMessage());
        }

        // Fill missing with single-call / heuristic
        for (HanVietRequest item : items) {
            if (item == null || item.id() == null) continue;
            if (!out.containsKey(item.id()) || out.get(item.id()).isBlank()) {
                out.put(item.id(), generateHanViet(item.front(), item.back()));
            }
        }
        return out;
    }

    @Override
    public String enrichImportText(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        List<Parsed> toFill = new ArrayList<>();
        List<String> lines = Arrays.asList(text.split("\\R"));
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.isBlank()) continue;
            Matcher m = LINE.matcher(raw.trim());
            if (!m.matches()) continue;
            String existing = m.group(4);
            if (existing != null && !existing.isBlank() && !existing.startsWith("AI-")) {
                continue;
            }
            toFill.add(new Parsed(i, m.group(1).trim(), m.group(2).trim(), m.group(3).trim()));
        }

        Map<Integer, String> hvByLine = new HashMap<>();
        if (!toFill.isEmpty() && hasValidKey()) {
            List<HanVietRequest> reqs = new ArrayList<>();
            for (Parsed p : toFill) {
                // use line index as temporary id
                reqs.add(new HanVietRequest((long) p.lineIndex, p.kanji, p.meaning));
            }
            Map<Long, String> batch = generateHanVietBatch(reqs);
            for (Parsed p : toFill) {
                String hv = batch.get((long) p.lineIndex);
                if (hv != null && !hv.isBlank()) {
                    hvByLine.put(p.lineIndex, hv);
                }
            }
        } else {
            for (Parsed p : toFill) {
                hvByLine.put(p.lineIndex, generateHanViet(p.kanji, p.meaning));
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.isBlank()) {
                sb.append(raw).append('\n');
                continue;
            }
            Matcher m = LINE.matcher(raw.trim());
            if (!m.matches()) {
                sb.append(raw).append('\n');
                continue;
            }
            String kanji = m.group(1).trim();
            String hira = m.group(2).trim();
            String meaning = m.group(3).trim();
            String existing = m.group(4);
            String hv = hvByLine.get(i);
            if (hv == null || hv.isBlank()) {
                if (existing != null && !existing.isBlank() && !existing.startsWith("AI-")) {
                    hv = existing.trim();
                } else {
                    hv = generateHanViet(kanji, meaning);
                }
            }
            sb.append(kanji).append(" - ").append(hira).append(" : ").append(meaning);
            if (hv != null && !hv.isBlank()) {
                sb.append(" (").append(hv).append(')');
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String callOpenRouter(String system, String user, int maxTokens) {
        if (!hasValidKey()) return null;
        Map<String, Object> body = new LinkedHashMap<>();
        // Ensure chat completions path
        String url = apiUrl;
        if (url != null && !url.contains("/chat/completions")) {
            url = url.endsWith("/") ? url + "chat/completions" : url + "/chat/completions";
        }
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.2);
        body.put("provider", Map.of(
                "order", List.of("Amazon Bedrock", "OpenAI", "Azure"),
                "allow_fallbacks", true
        ));
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)
        ));

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(apiKey);
        h.set("HTTP-Referer", "http://localhost:8080");
        h.set("X-Title", "JLAS Flashcard HanViet");

        try {
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> res = restTemplate.postForEntity(url, new HttpEntity<>(body, h), Map.class);
            if (res.getBody() == null) return null;
            List<?> choices = (List<?>) res.getBody().get("choices");
            if (choices == null || choices.isEmpty()) return null;
            Map<?, ?> msg = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
            Object content = msg.get("content");
            return content != null ? content.toString() : null;
        } catch (Exception e) {
            log.error("OpenRouter Hán Việt error: {}", e.getMessage());
            return null;
        }
    }

    private boolean hasValidKey() {
        return apiKey != null && !apiKey.isBlank()
                && !apiKey.contains("XXXX")
                && apiKey.startsWith("sk-");
    }

    private static String cleanHanViet(String raw, String front) {
        if (raw == null || raw.isBlank()) return "";
        String s = stripMarkdown(raw)
                .replaceAll("(?i)^han\\s*vi[eệ]t\\s*[:：]\\s*", "")
                .replaceAll("^[\"']|[\"']$", "")
                .replaceAll("\\s+", " ")
                .trim();
        // Reject bad stub-like outputs
        if (s.startsWith("AI-") || s.startsWith("AI ")) return "";
        if (front != null && s.equals(front.trim())) return "";
        // Keep short answers only
        if (s.length() > 40) {
            s = s.split("[.\\n;]")[0].trim();
        }
        if (s.length() > 40) return "";
        return s;
    }

    private static String stripMarkdown(String raw) {
        if (raw == null) return "";
        return raw.trim()
                .replaceAll("^```json\\s*", "")
                .replaceAll("^```\\s*", "")
                .replaceAll("```$", "")
                .trim();
    }

    /**
     * Lightweight fallback dictionary for common JLPT kanji compounds when AI is offline.
     */
    private static String heuristicHanViet(String front) {
        Map<String, String> known = Map.ofEntries(
                Map.entry("台風", "Đài phong"),
                Map.entry("位置", "Vị trí"),
                Map.entry("景色", "Cảnh sắc"),
                Map.entry("陽気", "Dương khí"),
                Map.entry("家族", "Gia tộc"),
                Map.entry("学校", "Học hiệu"),
                Map.entry("先生", "Tiên sinh"),
                Map.entry("学生", "Học sinh"),
                Map.entry("時間", "Thời gian"),
                Map.entry("電車", "Điện xa"),
                Map.entry("自動車", "Tự động xa"),
                Map.entry("映画", "Ảnh họa"),
                Map.entry("音楽", "Âm nhạc"),
                Map.entry("旅行", "Lữ hành"),
                Map.entry("料理", "Liệu lý"),
                Map.entry("勉強", "Miễn cưỡng"),
                Map.entry("加えて", "Gia"),
                Map.entry("加", "Gia")
        );
        if (known.containsKey(front)) {
            return known.get(front);
        }
        // Single-kanji map (partial)
        Map<Character, String> charMap = Map.ofEntries(
                Map.entry('台', "Đài"), Map.entry('風', "Phong"),
                Map.entry('位', "Vị"), Map.entry('置', "Trí"),
                Map.entry('景', "Cảnh"), Map.entry('色', "Sắc"),
                Map.entry('加', "Gia"), Map.entry('学', "Học"),
                Map.entry('校', "Hiệu"), Map.entry('時', "Thời"),
                Map.entry('間', "Gian"), Map.entry('電', "Điện"),
                Map.entry('車', "Xa"), Map.entry('音', "Âm"),
                Map.entry('楽', "Nhạc"), Map.entry('旅', "Lữ"),
                Map.entry('行', "Hành"), Map.entry('映', "Ảnh"),
                Map.entry('画', "Họa"), Map.entry('家', "Gia"),
                Map.entry('族', "Tộc"), Map.entry('先', "Tiên"),
                Map.entry('生', "Sinh"), Map.entry('勉', "Miễn"),
                Map.entry('強', "Cường"), Map.entry('料', "Liệu"),
                Map.entry('理', "Lý"), Map.entry('陽', "Dương"),
                Map.entry('気', "Khí")
        );
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < front.length(); i++) {
            char c = front.charAt(i);
            if (charMap.containsKey(c)) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(charMap.get(c));
            }
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean sameKanji(String left, String right) {
        return nullToEmpty(left).replaceAll("\\s+", "")
                .equals(nullToEmpty(right).replaceAll("\\s+", ""));
    }

    private static String textOr(JsonNode n, String field, String fallback) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return fallback != null ? fallback : "";
        }
        String v = n.get(field).asText("").trim();
        return v.isEmpty() ? (fallback != null ? fallback : "") : v;
    }

    private static String buildBack(String reading, String meaning) {
        String r = reading != null ? reading.trim() : "";
        String m = meaning != null ? meaning.trim() : "";
        if (!r.isEmpty() && !m.isEmpty()) {
            return r + " — " + m;
        }
        return !m.isEmpty() ? m : r;
    }

    private CardEnrichResult heuristicEnrich(String kanji) {
        String hv = heuristicHanViet(kanji);
        Map<String, String[]> known = Map.of(
                "台風", new String[]{"たいふう", "Cơn bão", "Đài phong"},
                "位置", new String[]{"いち", "Vị trí", "Vị trí"},
                "景色", new String[]{"けしき", "Phong cảnh", "Cảnh sắc"},
                "陽気", new String[]{"ようき", "Tiết trời thuận lợi; vui vẻ", "Dương khí"},
                "加えて", new String[]{"くわえて", "Thêm vào đó, ngoài ra", "Gia"},
                "家族", new String[]{"かぞく", "Gia đình", "Gia tộc"},
                "学校", new String[]{"がっこう", "Trường học", "Học hiệu"},
                "時間", new String[]{"じかん", "Thời gian", "Thời gian"}
        );
        if (known.containsKey(kanji)) {
            String[] a = known.get(kanji);
            return new CardEnrichResult(kanji, a[0], a[1], a[2], buildBack(a[0], a[1]));
        }
        String meaning = hv.isBlank() ? "" : hv;
        String reading = "";
        return new CardEnrichResult(kanji, reading, meaning, hv, buildBack(reading, meaning.isBlank() ? kanji : meaning));
    }

    private record Parsed(int lineIndex, String kanji, String reading, String meaning) {
    }
}
