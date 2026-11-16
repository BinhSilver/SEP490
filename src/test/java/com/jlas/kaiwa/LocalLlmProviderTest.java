package com.jlas.kaiwa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.ai.AiUsageRecorder;
import com.jlas.kaiwa.config.OpenRouterConfig;
import com.jlas.kaiwa.memory.MemoryAiClient;
import com.jlas.kaiwa.memory.MemoryProperties;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.kaiwa.service.ai.LocalLlmClient;
import com.jlas.kaiwa.service.ai.OpenRouterClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AI Call chạy trên Ollama cục bộ: chat qua API gốc {@code /api/chat} (tắt thinking, keep_alive),
 * embedding qua {@code /v1}. Dùng một HTTP server thật trên 127.0.0.1 đóng vai Ollama để kiểm tra đúng URL,
 * không gửi key/field riêng của OpenRouter, giữ nguyên tiếng Nhật UTF-8, timeout và lỗi không tự chuyển sang cloud.
 */
class LocalLlmProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    /** {@code null}: trả lời theo đường dẫn (API gốc hoặc kiểu OpenAI) với câu tiếng Nhật cố định. */
    private volatile String responseBody = null;
    private volatile long delayMs = 0;

    private record Recorded(String path, String authorization, String referer, JsonNode body) { }

    @BeforeEach
    void startFakeOllama() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] in = exchange.getRequestBody().readAllBytes();
            requests.add(new Recorded(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("HTTP-Referer"),
                    JSON.readTree(new String(in, StandardCharsets.UTF_8))));
            if (delayMs > 0) {
                try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            String path = exchange.getRequestURI().getPath();
            String body = responseBody != null ? responseBody
                    : path.equals("/api/chat") ? nativeBody("はい、わかりました。") : chatBody("はい、わかりました。");
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
    }

    private OpenRouterConfig config(String provider, int readTimeoutMs) {
        OpenRouterConfig config = new OpenRouterConfig();
        ReflectionTestUtils.setField(config, "apiKey", "sk-or-secret");
        ReflectionTestUtils.setField(config, "apiUrl", baseUrl().replaceAll("/$", ""));
        ReflectionTestUtils.setField(config, "siteUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(config, "siteName", "JLAS");
        ReflectionTestUtils.setField(config, "model", "openai/gpt-4o-mini");
        ReflectionTestUtils.setField(config, "reasoningEffort", "low");
        ReflectionTestUtils.setField(config, "provider", provider);
        ReflectionTestUtils.setField(config, "ollamaBaseUrl", baseUrl());
        ReflectionTestUtils.setField(config, "ollamaModel", "qwen3.5:4b");
        ReflectionTestUtils.setField(config, "ollamaEmbeddingModel", "bge-m3");
        ReflectionTestUtils.setField(config, "ollamaKeepAlive", "30m");
        ReflectionTestUtils.setField(config, "ollamaThink", false);
        ReflectionTestUtils.setField(config, "ollamaNumCtx", 4096);
        ReflectionTestUtils.setField(config, "connectTimeoutMs", 2_000);
        ReflectionTestUtils.setField(config, "readTimeoutMs", readTimeoutMs);
        return config;
    }

    private OpenRouterClient client(OpenRouterConfig config) {
        return new OpenRouterClient(config, mock(AiUsageRecorder.class));
    }

    private static String chatBody(String content) {
        try {
            return JSON.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", content)))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nativeBody(String content) {
        try {
            return JSON.writeValueAsString(Map.of("model", "qwen3.5:4b", "done", true,
                    "message", Map.of("role", "assistant", "content", content)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void ollamaChatUsesLocalModelWithoutCloudKeyOrOpenRouterExtensions() {
        OpenRouterClient client = client(config("ollama", 5_000));

        String reply = client.complete("Bạn là gia sư tiếng Nhật.", List.of(), "「食べる」の意味は？");

        assertThat(reply).isEqualTo("はい、わかりました。");
        Recorded request = requests.getFirst();
        assertThat(request.path()).isEqualTo("/api/chat");
        assertThat(request.authorization()).isNull();
        assertThat(request.referer()).isNull();
        assertThat(request.body().path("model").asText()).isEqualTo("qwen3.5:4b");
        assertThat(request.body().has("reasoning")).isFalse();
        assertThat(request.body().path("think").isBoolean()).isTrue();
        assertThat(request.body().path("think").asBoolean()).isFalse();
        assertThat(request.body().path("stream").asBoolean()).isFalse();
        assertThat(request.body().path("keep_alive").asText()).isEqualTo("30m");
        assertThat(request.body().path("options").path("num_predict").asInt()).isEqualTo(900);
        assertThat(request.body().path("options").path("num_ctx").asInt()).isEqualTo(4096);
        assertThat(request.body().path("messages").get(1).path("content").asText()).isEqualTo("「食べる」の意味は？");
    }

    @Test
    void ollamaGraphTurnKeepsStrictJsonSchema() {
        OpenRouterClient client = client(config("ollama", 5_000));

        client.completeGraph("system", List.of(), "すみません");

        // Ollama ép đầu ra theo đúng JSON schema khi "format" là schema.
        JsonNode format = requests.getFirst().body().path("format");
        assertThat(format.path("type").asText()).isEqualTo("object");
        assertThat(format.path("required")).hasSize(11);
        assertThat(format.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(requests.getFirst().body().path("options").path("num_predict").asInt()).isEqualTo(2048);
    }

    @Test
    void ollamaStructuredReplyAsksForJsonAndIgnoresThinkingField() {
        responseBody = "{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"reply\\\":\\\"はい\\\"}\",\"thinking\":\"...\"},\"done\":true}";
        OpenRouterClient client = client(config("ollama", 5_000));

        assertThat(client.completeStructured("system", List.of(), "すみません")).isEqualTo("{\"reply\":\"はい\"}");
        assertThat(requests.getFirst().body().path("format").asText()).isEqualTo("json");
    }

    @Test
    void thinkingCanBeEnabledExplicitly() {
        OpenRouterConfig config = config("ollama", 5_000);
        ReflectionTestUtils.setField(config, "ollamaThink", true);

        client(config).complete("s", List.of(), "u");

        assertThat(requests.getFirst().body().path("think").asBoolean()).isTrue();
    }

    @Test
    void openRouterStaysTheDefaultAndKeepsItsHeaders() {
        OpenRouterClient client = client(config("anything-else", 5_000));

        client.complete("system", List.of(), "こんにちは");

        Recorded request = requests.getFirst();
        assertThat(request.authorization()).isEqualTo("Bearer sk-or-secret");
        assertThat(request.referer()).isEqualTo("http://localhost:8080");
        assertThat(request.body().path("model").asText()).isEqualTo("openai/gpt-4o-mini");
        assertThat(request.body().has("reasoning")).isTrue();
    }

    @Test
    void slowLocalModelFailsWithinConfiguredTimeoutWithoutRetryingTheRead() {
        delayMs = 2_500;
        OpenRouterClient client = client(config("ollama", 1_000));

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.complete("system", List.of(), "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Ollama call failed");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs).isLessThan(2_400);
        assertThat(requests).hasSize(1);
    }

    @Test
    void unavailableLocalModelIsReportedNotReplacedByCloud() {
        status = 404;
        responseBody = "{\"error\":{\"message\":\"model \\\"qwen3.5:4b\\\" not found, try pulling it first\"}}";
        OpenRouterClient client = client(config("ollama", 5_000));

        OpenRouterClient.ProbeResult probe = client.probe();

        assertThat(probe.ok()).isFalse();
        assertThat(probe.provider()).isEqualTo("ollama");
        assertThat(probe.error()).isEqualTo("HTTP_404");
        assertThat(requests).allSatisfy(r -> assertThat(r.path()).isEqualTo("/api/chat"));
    }

    @Test
    void probeReportsUnreachableServer() {
        OpenRouterConfig config = config("ollama", 1_000);
        ReflectionTestUtils.setField(config, "ollamaBaseUrl", "http://127.0.0.1:1/v1");

        OpenRouterClient.ProbeResult probe = client(config).probe();

        assertThat(probe.ok()).isFalse();
        assertThat(probe.error()).isEqualTo("UNREACHABLE_OR_TIMEOUT");
    }

    @Test
    void failedCallDoesNotExposeInternalAddressToLearners() {
        OpenRouterConfig config = config("ollama", 1_000);
        ReflectionTestUtils.setField(config, "ollamaBaseUrl", "http://127.0.0.1:1/v1");

        assertThatThrownBy(() -> client(config).complete("s", List.of(), "こんにちは"))
                .hasMessageStartingWith("Ollama call failed")
                .message().doesNotContain("127.0.0.1").doesNotContain("/api/chat");
    }

    @Test
    void probeMeasuresSuccessfulReply() {
        responseBody = nativeBody("hai");

        OpenRouterClient.ProbeResult probe = client(config("ollama", 5_000)).probe();

        assertThat(probe.ok()).isTrue();
        assertThat(probe.model()).isEqualTo("qwen3.5:4b");
        assertThat(probe.replyChars()).isEqualTo(3);
        assertThat(requests.getFirst().body().path("options").path("num_predict").asInt()).isEqualTo(16);
    }

    @Test
    void memoryClientUsesLocalEmbeddingModelWithoutApiKey() {
        responseBody = "{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}";
        MemoryAiClient memory = new MemoryAiClient(new ObjectMapper(),
                new MemoryProperties(null, 0, 0, 0, 0, 0), config("ollama", 5_000));

        assertThat(memory.available()).isTrue();
        assertThat(memory.embed("毎朝ジョギングします")).containsExactly(0.1, 0.2, 0.3);
        assertThat(memory.embeddingModel()).isEqualTo("bge-m3#user-evidence-v1");

        Recorded request = requests.getFirst();
        assertThat(request.path()).isEqualTo("/v1/embeddings");
        assertThat(request.authorization()).isNull();
        assertThat(request.body().path("model").asText()).isEqualTo("bge-m3");
    }

    @Test
    void memoryClientStillNeedsKeyForOpenRouter() {
        OpenRouterConfig config = config("openrouter", 5_000);
        ReflectionTestUtils.setField(config, "apiKey", "");
        MemoryAiClient memory = new MemoryAiClient(new ObjectMapper(), new MemoryProperties(null, 0, 0, 0, 0, 0), config);

        assertThat(memory.available()).isFalse();
        assertThat(memory.embeddingModel()).isEqualTo("openai/text-embedding-3-small#user-evidence-v1");
    }

    @Test
    void localMemoryChatUsesOllamaModel() {
        MemoryAiClient memory = new MemoryAiClient(new ObjectMapper(),
                new MemoryProperties(null, 0, 0, 0, 0, 0), config("ollama", 5_000));
        ReflectionTestUtils.setField(memory, "model", "openai/gpt-4o-mini");

        assertThat(memory.reply(List.of(Map.of("role", "user", "content", "こんにちは")))).isEqualTo("はい、わかりました。");
        Recorded request = requests.getFirst();
        assertThat(request.path()).isEqualTo("/api/chat");
        assertThat(request.body().path("model").asText()).isEqualTo("qwen3.5:4b");
        assertThat(request.body().path("think").asBoolean(true)).isFalse();
    }

    @Test
    void concurrentRequestsAreAllServed() throws Exception {
        delayMs = 100;
        OpenRouterClient client = client(config("ollama", 5_000));
        AtomicInteger ok = new AtomicInteger();
        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread thread = new Thread(() -> {
                if (!client.complete("s", List.of(), "u").isBlank()) ok.incrementAndGet();
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) thread.join(5_000);
        assertThat(ok.get()).isEqualTo(4);
    }

    @Test
    void tutorClientAlwaysUsesLocalOllamaEvenWhenAiCallUsesOpenRouter() {
        LocalLlmClient tutor = new LocalLlmClient(config("openrouter", 5_000), JSON);

        assertThat(tutor.chat("system", List.of(Map.of("role", "user", "content", "前の質問")), "勉強", 700))
                .isEqualTo("はい、わかりました。");
        Recorded request = requests.getFirst();
        assertThat(request.path()).isEqualTo("/api/chat");
        assertThat(request.authorization()).isNull();
        assertThat(request.body().path("model").asText()).isEqualTo("qwen3.5:4b");
        assertThat(request.body().path("think").asBoolean(true)).isFalse();
        assertThat(request.body().path("options").path("num_predict").asInt()).isEqualTo(700);
        assertThat(request.body().path("messages")).hasSize(3);
    }

    @Test
    void tutorClientReportsUnreachableModelWithoutInternalAddress() {
        OpenRouterConfig unreachable = config("openrouter", 1_000);
        ReflectionTestUtils.setField(unreachable, "ollamaBaseUrl", "http://127.0.0.1:1/v1");

        assertThatThrownBy(() -> new LocalLlmClient(unreachable, JSON).chat("s", List.of(), "u", 50))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain("127.0.0.1");
                });
        assertThat(requests).isEmpty();
    }

    private OpenRouterConfig tutorApi(String key) {
        OpenRouterConfig config = config("openrouter", 5_000);
        ReflectionTestUtils.setField(config, "tutorProvider", "api");
        ReflectionTestUtils.setField(config, "tutorBaseUrl", baseUrl());
        ReflectionTestUtils.setField(config, "tutorApiKey", key);
        ReflectionTestUtils.setField(config, "tutorModel", "deepseek-chat");
        ReflectionTestUtils.setField(config, "tutorReasoningEffort", "none");
        return config;
    }

    @Test
    void tutorApiModeCallsOpenAiCompatibleEndpointWithItsOwnKey() {
        LocalLlmClient tutor = new LocalLlmClient(tutorApi("sk-deepseek-secret"), JSON);

        assertThat(tutor.chat("system", List.of(), "勉強", 700)).isEqualTo("はい、わかりました。");
        assertThat(tutor.model()).isEqualTo("deepseek-chat");
        Recorded request = requests.getFirst();
        assertThat(request.path()).isEqualTo("/v1/chat/completions");
        assertThat(request.authorization()).isEqualTo("Bearer sk-deepseek-secret");
        assertThat(request.body().path("model").asText()).isEqualTo("deepseek-chat");
        assertThat(request.body().path("max_tokens").asInt()).isEqualTo(700);
        assertThat(request.body().path("stream").asBoolean(true)).isFalse();
        assertThat(request.body().has("think")).isFalse();
        assertThat(request.body().path("reasoning_effort").asText()).isEqualTo("none");
    }

    @Test
    void tutorApiModeStreamsServerSentEvents() {
        responseBody = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\n\n"
                + ": keep-alive\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"はい、\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"そうです。\"}}]}\n\n"
                + "data: [DONE]\n\n";
        List<String> deltas = new CopyOnWriteArrayList<>();

        String answer = new LocalLlmClient(tutorApi("sk-deepseek-secret"), JSON).chatStream("s", List.of(), "u", 50, deltas::add);

        assertThat(answer).isEqualTo("はい、そうです。");
        assertThat(deltas).containsExactly("はい、", "そうです。");
        assertThat(requests.getFirst().body().path("stream").asBoolean()).isTrue();
    }

    @Test
    void tutorApiErrorIsReportedWithoutLeakingTheKeyAndWithoutFallingBackToOllama() {
        status = 401;
        responseBody = "{\"error\":{\"message\":\"Authentication Fails, Your api key: ****cret is invalid\"}}";

        assertThatThrownBy(() -> new LocalLlmClient(tutorApi("sk-deepseek-secret"), JSON).chat("s", List.of(), "u", 50))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR);
                    assertThat(e.getMessage()).doesNotContain("cret");
                });
        assertThat(requests).extracting(Recorded::path).containsExactly("/v1/chat/completions");
    }

    @Test
    void tutorApiWithoutKeyStaysOnOllama() {
        LocalLlmClient tutor = new LocalLlmClient(tutorApi(""), JSON);

        tutor.chat("s", List.of(), "u", 50);
        assertThat(requests.getFirst().path()).isEqualTo("/api/chat");
        assertThat(tutor.model()).isEqualTo("qwen3.5:4b");
    }
}
