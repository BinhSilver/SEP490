package com.jlas.kaiwa.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.kaiwa.config.OpenRouterConfig;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Embedding qua OpenRouter; prompt chỉ đưa nguồn có thật và đánh mã để model trích lại. */
class RagEmbedderAndPromptTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static OpenRouterConfig openRouter(String apiUrl) {
        OpenRouterConfig config = new OpenRouterConfig();
        ReflectionTestUtils.setField(config, "apiKey", "sk-or-secret");
        ReflectionTestUtils.setField(config, "apiUrl", apiUrl);
        return config;
    }

    private static RagProperties properties(int timeoutMs) {
        return new RagProperties(true, "baai/bge-m3", "jlas_content", 16, 5, 0.45, false, false, timeoutMs);
    }

    @Test
    void embedderBatchesThroughOpenRouterEmbeddings() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<JsonNode> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] out = "{\"data\":[{\"embedding\":[0.1,0.2]},{\"embedding\":[0.3,0.4]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            RagEmbedder embedder = new RagEmbedder(openRouter("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1"),
                    properties(5_000), JSON);

            List<float[]> vectors = embedder.embed(List.of("勉強", "べんきょう"));

            assertThat(vectors).hasSize(2);
            assertThat(vectors.get(1)).containsExactly(0.3f, 0.4f);
            assertThat(path.get()).isEqualTo("/api/v1/embeddings");
            assertThat(auth.get()).isEqualTo("Bearer sk-or-secret");
            assertThat(body.get().path("model").asText()).isEqualTo("baai/bge-m3");
            assertThat(body.get().path("input")).hasSize(2);
            // Số vector không khớp số đoạn thì không ghi gì vào index.
            assertThatThrownBy(() -> embedder.embed(List.of("một đoạn"))).isInstanceOf(IllegalStateException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unreachableEmbeddingApiGivesCleanServiceUnavailable() {
        assertThatThrownBy(() -> new RagEmbedder(openRouter("http://127.0.0.1:1/api/v1"), properties(1_000), JSON).embed(List.of("勉強")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE))
                .message().doesNotContain("127.0.0.1");
    }

    @Test
    void wordCandidatesCoverVerbStemsAndKanjiRuns() {
        assertThat(RagLexiconLookup.wordCandidates("「勉強する」nghĩa là gì?")).contains("勉強する", "勉強");
        assertThat(RagLexiconLookup.kanjiCandidates("食べる と 勉強")).containsExactly("食", "勉", "強");
        assertThat(RagLexiconLookup.wordCandidates("xin chào")).isEmpty();
    }

    @Test
    void promptNumbersDictionaryAndTextbookSources() {
        var kanji = new RagLexiconLookup.KanjiEntry(1, "勉", "Miễn", null, "N5", List.of("ベン"), List.of("つと.める"));
        var word = new RagLexiconLookup.WordEntry(2, "勉強", "べんきょう", "việc học", null, "N5");
        var passage = new RagRetriever.Passage("id", 0.8, "GRAMMAR", "N1 は N2 です", "Mẫu ngữ pháp: N1 は N2 です",
                Map.of("textbook", "Sekai N5", "lesson", "Bài 1"));

        String prompt = RagPromptComposer.systemPrompt(new RagRetriever.Context(List.of(passage),
                new RagLexiconLookup.Result(List.of(word), List.of(kanji))));

        assertThat(prompt).contains("[D1] Kanji 勉 | Hán Việt: Miễn | On: ベン | Kun: つと.める",
                "[D2] Từ 勉強 (べんきょう) | Nghĩa: việc học", "[S1] Sekai N5 › Bài 1", "CHỈ lấy từ phần NGUỒN");
        assertThat(RagPromptComposer.systemPrompt(new RagRetriever.Context(List.of(), new RagLexiconLookup.Result(List.of(), List.of()))))
                .contains("(không có nguồn phù hợp)");
    }

    @Test
    void citationsUseTheSameCodesAsThePrompt() {
        var kanji = new RagLexiconLookup.KanjiEntry(1, "勉", "Miễn", null, "N5", List.of("ベン"), List.of());
        var word = new RagLexiconLookup.WordEntry(2, "勉強", "べんきょう", "việc học", null, "N5");
        var passage = new RagRetriever.Passage("id", 0.8, "GRAMMAR", "N1 は N2 です", "...",
                Map.of("textbook", "Sekai N5", "lesson", "Bài 1"));

        var citations = RagPromptComposer.citations(new RagRetriever.Context(List.of(passage),
                new RagLexiconLookup.Result(List.of(word), List.of(kanji))));

        assertThat(citations).extracting(RagPromptComposer.Citation::code).containsExactly("D1", "D2", "S1");
        assertThat(citations.get(1).title()).isEqualTo("勉強 (べんきょう)");
        assertThat(citations.get(2).detail()).isEqualTo("Sekai N5 › Bài 1");
    }

    @Test
    void dictionaryOnlyContextSaysTextbookHasNoMatchingPassage() {
        var kanji = new RagLexiconLookup.KanjiEntry(1, "強", "Cường, Cưỡng", null, "N4", List.of("キョウ"), List.of("つよ.い"));

        String prompt = RagPromptComposer.systemPrompt(new RagRetriever.Context(List.of(),
                new RagLexiconLookup.Result(List.of(), List.of(kanji))));

        assertThat(prompt).contains("[D1] Kanji 強 | Hán Việt: Cường, Cưỡng", "[S] (không có đoạn giáo trình nào khớp câu hỏi)",
                "Tài liệu J-LAS chưa có mẫu này.", "Tuyệt đối không viết romaji", "Mình chỉ hỗ trợ học tiếng Nhật trên J-LAS", "Không tự giải thích bằng hiểu biết ngoài NGUỒN",
                "không dùng tiêu đề (#) và không dùng bảng", "không suy ra nghĩa của từ ghép từ nghĩa từng kanji",
                "không gọi cách đọc của từ là On hay Kun");
        assertThat(prompt).doesNotContain("[S1]");
    }
}
