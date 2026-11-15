package com.jlas.flashcard.service;

import com.jlas.flashcard.dto.AiDtos.*;
import com.jlas.flashcard.entity.FlashcardCard;
import com.jlas.flashcard.repository.FlashcardCardRepository;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiLookupService {

    @Value("${openrouter.api.key:}")
    private String apiKey;

    @Value("${openrouter.api.url:https://openrouter.ai/api/v1/chat/completions}")
    private String apiUrl;

    @Value("${openrouter.site.url:http://localhost:8080}")
    private String siteUrl;

    @Value("${openrouter.model:google/gemma-3-27b-it:free}")
    private String model;

    private final FlashcardCardRepository cardRepo;
    private final ObjectMapper objectMapper;

    private static final String SYS_EXPLAIN = """
        Bạn là trợ lý học tiếng Nhật cho người Việt. Phân tích từ vựng và trả JSON thuần:
        {
          "type":"explain",
          "term":"",
          "reading":"",
          "meaning":"",
          "hanViet":null,
          "jlptLevel":null,
          "usage":"cách dùng 1-2 câu",
          "tip":"mẹo ghi nhớ",
          "kanjiParts":[{"character":"","onyomi":"","kunyomi":"","hanViet":"","meaning":""}],
          "examples":[{"japanese":"","reading":"","vietnamese":""}]
        }
        Không markdown, chỉ JSON.
        """;

    private static final String SYS_EXAMPLES = """
        Sinh ví dụ tiếng Nhật theo 3 cấp N5, N4, N3. Trả JSON thuần:
        {
          "type":"examples",
          "term":"",
          "exampleGroups":[
            {"level":"N5","examples":[{"japanese":"","reading":"","vietnamese":"","explanation":""}]},
            {"level":"N4","examples":[{"japanese":"","reading":"","vietnamese":"","explanation":""}]},
            {"level":"N3","examples":[{"japanese":"","reading":"","vietnamese":"","explanation":""}]}
          ]
        }
        Mỗi level đúng 3 câu. JSON thuần.
        """;

    private static final String SYS_KANJI = """
        Phân tích từng chữ kanji trong từ. Trả JSON thuần:
        {
          "type":"kanji",
          "term":"",
          "reading":"",
          "meaning":"",
          "hanViet":null,
          "usage":"giải thích vì sao ghép thành nghĩa này",
          "kanjiParts":[{"character":"","onyomi":"","kunyomi":"","hanViet":"","meaning":""}]
        }
        JSON thuần.
        """;

    private static final String SYS_CHAT = """
        Bạn là gia sư tiếng Nhật thân thiện. Trả lời bằng tiếng Việt, ngắn gọn, rõ ràng.
        Có thể dùng ví dụ tiếng Nhật kèm furigana. Trả JSON:
        {"type":"chat","term":"","reply":"nội dung trả lời markdown nhẹ","meaning":null}
        """;

    private static final String SYS_VOCAB = """
        Bạn là trợ lý học tiếng Nhật. Tra từ vựng và trả lời ĐÚNG JSON:
        {"type":"vocabulary","term":"","reading":"","meaning":"","hanViet":null,"jlptLevel":null,
         "usage":"","examples":[{"japanese":"","reading":"","vietnamese":""}]}
        """;

    public AiLookupResponse lookup(AiLookupRequest req) {
        String type = req.getType() != null ? req.getType().toLowerCase() : "auto";
        String term = resolveTerm(req);

        FlashcardCard card = null;
        if (req.getCardId() != null) {
            card = cardRepo.findById(req.getCardId()).orElse(null);
            if (card != null && (term == null || term.isBlank())) {
                term = card.getFront();
            }
        }

        String system = switch (type) {
            case "explain" -> SYS_EXPLAIN;
            case "examples" -> SYS_EXAMPLES;
            case "kanji" -> SYS_KANJI;
            case "chat" -> SYS_CHAT;
            case "grammar" -> SYS_VOCAB;
            default -> SYS_VOCAB;
        };

        String userMsg = buildUserMessage(type, term, req, card);
        String raw = callOpenRouter(system, userMsg);

        if (raw == null || raw.isBlank()) {
            return unavailableResponse(type, term);
        }

        AiLookupResponse parsed = parseResponse(raw, term, type);
        if (parsed.getMeaning() == null && parsed.getReply() == null
                && (parsed.getExamples() == null || parsed.getExamples().isEmpty())
                && (parsed.getExampleGroups() == null || parsed.getExampleGroups().isEmpty())
                && (parsed.getKanjiParts() == null || parsed.getKanjiParts().isEmpty())) {
            return unavailableResponse(type, term);
        }
        parsed.setMock(false);
        return parsed;
    }

    public AiLookupResponse chat(AiChatRequest req) {
        AiLookupRequest lookup = new AiLookupRequest();
        lookup.setCardId(req.getCardId());
        lookup.setTerm(req.getTerm());
        lookup.setType("chat");
        lookup.setMessage(req.getMessage());
        lookup.setContext(req.getContext());
        return lookup(lookup);
    }

    private String resolveTerm(AiLookupRequest req) {
        if (req.getTerm() != null && !req.getTerm().isBlank()) return req.getTerm().trim();
        return null;
    }

    private String buildUserMessage(String type, String term, AiLookupRequest req, FlashcardCard card) {
        StringBuilder sb = new StringBuilder();
        sb.append("Từ: ").append(term != null ? term : "");
        if (card != null) {
            sb.append("\nHiragana: ").append(card.getReading());
            sb.append("\nNghĩa: ").append(card.getMeaning());
            if (card.getHanViet() != null) sb.append("\nHán Việt: ").append(card.getHanViet());
        }
        if (req.getContext() != null) sb.append("\nContext: ").append(req.getContext());
        if (req.getMessage() != null) sb.append("\nCâu hỏi: ").append(req.getMessage());
        if ("examples".equals(type)) sb.append("\nTạo 3 câu N5, 3 câu N4, 3 câu N3.");
        if ("kanji".equals(type)) sb.append("\nPhân tích từng kanji.");
        if ("explain".equals(type)) sb.append("\nGiải thích chi tiết + mẹo ghi nhớ.");
        return sb.toString();
    }

    private final RestTemplate restTemplate = new RestTemplate();

    private boolean hasValidKey() {
        String key = apiKey == null ? "" : apiKey.trim();
        return !key.isBlank() && !key.contains("XXXX") && key.startsWith("sk-");
    }

    private String callOpenRouter(String system, String user) {
        if (!hasValidKey()) {
            log.info("OpenRouter key missing — using mock");
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 1400);
        body.put("temperature", 0.4);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)
        ));

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(apiKey.trim());
        h.set("HTTP-Referer", siteUrl);
        h.set("X-Title", "Japanese Flashcard");

        try {
            String endpoint = apiUrl == null ? "" : apiUrl.trim();
            if (!endpoint.endsWith("/chat/completions")) {
                endpoint = endpoint.endsWith("/")
                        ? endpoint + "chat/completions"
                        : endpoint + "/chat/completions";
            }
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> res = restTemplate.postForEntity(endpoint, new HttpEntity<>(body, h), Map.class);
            if (res.getBody() == null) return null;
            List<?> choices = (List<?>) res.getBody().get("choices");
            if (choices == null || choices.isEmpty()) return null;
            Map<?, ?> msg = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
            Object content = msg == null ? null : msg.get("content");
            if (content instanceof String text) return text;
            return content == null ? null : objectMapper.writeValueAsString(content);
        } catch (HttpStatusCodeException e) {
            log.error("OpenRouter error {}: {}", e.getStatusCode().value(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.error("OpenRouter error: {}", e.getMessage());
            return null;
        }
    }

    private AiLookupResponse unavailableResponse(String type, String term) {
        String message = hasValidKey()
                ? "AI không phản hồi. Kiểm tra model hoặc cấu hình OpenRouter trên server."
                : "AI chưa được cấu hình. Hãy đặt OPENROUTER_API_KEY trên server.";
        return AiLookupResponse.builder()
                .type(type)
                .term(term)
                .error(message)
                .mock(false)
                .build();
    }

    @SuppressWarnings("unchecked")
    private AiLookupResponse parseResponse(String raw, String fallback, String type) {
        try {
            String clean = raw.trim()
                    .replaceAll("^```json\\s*", "")
                    .replaceAll("^```\\s*", "")
                    .replaceAll("```$", "")
                    .trim();
            Map<String, Object> m = objectMapper.readValue(clean, Map.class);

            AiLookupResponse.AiLookupResponseBuilder b = AiLookupResponse.builder()
                    .type(str(m.getOrDefault("type", type)))
                    .term(str(m.getOrDefault("term", fallback)))
                    .reading(str(m.get("reading")))
                    .meaning(str(m.get("meaning")))
                    .hanViet(str(m.get("hanViet")))
                    .jlptLevel(str(m.get("jlptLevel")))
                    .grammarPoint(str(m.get("grammarPoint")))
                    .usage(str(m.get("usage")))
                    .tip(str(m.get("tip")))
                    .reply(str(m.get("reply")))
                    .rawMarkdown(raw)
                    .mock(false);

            if (m.get("examples") instanceof List<?> exList) {
                b.examples(parseExamples(exList));
            }
            if (m.get("exampleGroups") instanceof List<?> groups) {
                List<ExampleGroupDto> gs = new ArrayList<>();
                for (Object g : groups) {
                    if (g instanceof Map<?, ?> gm) {
                        List<ExampleDto> exs = gm.get("examples") instanceof List<?> el
                                ? parseExamples(el) : List.of();
                        gs.add(ExampleGroupDto.builder()
                                .level(str(gm.get("level")))
                                .examples(exs)
                                .build());
                    }
                }
                b.exampleGroups(gs);
            }
            if (m.get("kanjiParts") instanceof List<?> parts) {
                List<KanjiPartDto> kps = new ArrayList<>();
                for (Object p : parts) {
                    if (p instanceof Map<?, ?> pm) {
                        kps.add(KanjiPartDto.builder()
                                .character(str(pm.get("character")))
                                .onyomi(str(pm.get("onyomi")))
                                .kunyomi(str(pm.get("kunyomi")))
                                .hanViet(str(pm.get("hanViet")))
                                .meaning(str(pm.get("meaning")))
                                .build());
                    }
                }
                b.kanjiParts(kps);
            }
            return b.build();
        } catch (Exception e) {
            log.error("Parse AI response failed: {}", e.getMessage());
            return AiLookupResponse.builder()
                    .type(type).term(fallback)
                    .rawMarkdown(raw)
                    .reply(raw)
                    .meaning("Phản hồi AI (raw)")
                    .mock(false)
                    .build();
        }
    }

    private List<ExampleDto> parseExamples(List<?> exList) {
        List<ExampleDto> exs = new ArrayList<>();
        for (Object ex : exList) {
            if (ex instanceof Map<?, ?> em) {
                exs.add(ExampleDto.builder()
                        .japanese(str(em.get("japanese")))
                        .reading(str(em.get("reading")))
                        .vietnamese(str(em.get("vietnamese")))
                        .explanation(str(em.get("explanation")))
                        .level(str(em.get("level")))
                        .build());
            }
        }
        return exs;
    }

    private AiLookupResponse mockResponse(String type, String term, FlashcardCard card, String message) {
        String t = term != null ? term : (card != null ? card.getFront() : "？");
        String reading = card != null ? card.getReading() : "";
        String meaning = card != null ? card.getMeaning() : "Chưa có dữ liệu";
        String hanViet = card != null ? card.getHanViet() : null;

        return switch (type) {
            case "examples" -> AiLookupResponse.builder()
                    .type("examples").term(t).mock(true)
                    .exampleGroups(List.of(
                            mockGroup("N5", t, reading, meaning),
                            mockGroup("N4", t, reading, meaning),
                            mockGroup("N3", t, reading, meaning)
                    ))
                    .usage("Dữ liệu demo (mock) — cấu hình openrouter.api.key để dùng AI thật.")
                    .build();
            case "kanji" -> AiLookupResponse.builder()
                    .type("kanji").term(t).reading(reading).meaning(meaning).hanViet(hanViet)
                    .mock(true)
                    .usage(hanViet != null
                            ? t + " ≈ " + hanViet + " — ghép nghĩa Hán Việt giúp nhớ nhanh."
                            : "Phân tích demo. Bật OpenRouter để phân tích chính xác hơn.")
                    .kanjiParts(splitKanjiParts(t, hanViet, meaning))
                    .build();
            case "chat" -> AiLookupResponse.builder()
                    .type("chat").term(t).mock(true)
                    .reply(buildMockChat(t, reading, meaning, hanViet, message))
                    .build();
            default -> AiLookupResponse.builder()
                    .type("explain").term(t).reading(reading).meaning(meaning).hanViet(hanViet)
                    .mock(true)
                    .usage("Từ **" + t + "** (" + reading + ") nghĩa là: " + meaning + ".")
                    .tip(hanViet != null
                            ? "Ghi nhớ qua Hán Việt: **" + hanViet + "**  liên hệ với nghĩa \"" + meaning + "\"."
                            : "Đọc to hiragana **" + reading + "** vài lần và đặt vào 1 câu ngắn.")
                    .kanjiParts(splitKanjiParts(t, hanViet, meaning))
                    .examples(List.of(
                            ExampleDto.builder()
                                    .japanese(t + "ですね。")
                                    .reading(reading + "ですね。")
                                    .vietnamese("Đúng là " + meaning + " nhỉ.")
                                    .build()
                    ))
                    .build();
        };
    }

    private ExampleGroupDto mockGroup(String level, String term, String reading, String meaning) {
        List<ExampleDto> list = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            list.add(ExampleDto.builder()
                    .japanese("これは" + term + "です。(" + level + "-" + i + ")")
                    .reading("これは" + reading + "です。")
                    .vietnamese("Đây là " + meaning + " (ví dụ " + level + " #" + i + ").")
                    .explanation("Câu mẫu cấp " + level + " dùng từ \"" + term + "\".")
                    .level(level)
                    .build());
        }
        return ExampleGroupDto.builder().level(level).examples(list).build();
    }

    private List<KanjiPartDto> splitKanjiParts(String term, String hanViet, String meaning) {
        List<KanjiPartDto> parts = new ArrayList<>();
        if (term == null) return parts;
        String[] hvParts = hanViet != null ? hanViet.trim().split("\\s+") : new String[0];
        int idx = 0;
        for (int i = 0; i < term.length(); i++) {
            char ch = term.charAt(i);
            if (Character.UnicodeScript.of(ch) != Character.UnicodeScript.HAN) continue;
            String hv = idx < hvParts.length ? hvParts[idx] : "";
            parts.add(KanjiPartDto.builder()
                    .character(String.valueOf(ch))
                    .onyomi("—")
                    .kunyomi("—")
                    .hanViet(hv)
                    .meaning(idx == 0 ? meaning : "thành phần kanji")
                    .build());
            idx++;
        }
        if (parts.isEmpty()) {
            parts.add(KanjiPartDto.builder()
                    .character(term)
                    .hanViet(hanViet)
                    .meaning(meaning)
                    .onyomi("—").kunyomi("—")
                    .build());
        }
        return parts;
    }

    private String buildMockChat(String term, String reading, String meaning, String hanViet, String message) {
        String q = message != null ? message : "Giải thích từ này";
        return "### " + term + "（" + reading + "）\n\n"
                + "**Nghĩa:** " + meaning + "\n"
                + (hanViet != null ? "**Hán Việt:** " + hanViet + "\n" : "")
                + "\n**Trả lời cho:** _" + q + "_\n\n"
                + "Đây là phản hồi demo (mock). Thêm `openrouter.api.key` vào `application.properties` "
                + "để AI trả lời chi tiết theo ngữ cảnh học của bạn.";
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
