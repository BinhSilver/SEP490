package com.jlas.kaiwa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.kaiwa.service.ai.TutorLlmClient;
import com.jlas.kaiwa.service.ai.TutorLlmProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Gia sư AI gọi một API kiểu OpenAI (DeepSeek qua Deploy...): trả lời thường, trả lời chữ dần (SSE), và báo lỗi an toàn. */
class TutorLlmClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String responseBody = null;
    /** Số yêu cầu đầu tiên bị treo (không trả gì trong 3 giây), giả lập nhà cung cấp thỉnh thoảng không phản hồi. */
    private volatile int hangFirst = 0;

    private record Recorded(String path, String authorization, JsonNode body) { }

    @BeforeEach
    void startFakeApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            byte[] in = exchange.getRequestBody().readAllBytes();
            requests.add(new Recorded(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization"),
                    JSON.readTree(new String(in, StandardCharsets.UTF_8))));
            if (requests.size() <= hangFirst) {
                try { Thread.sleep(3_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            String body = responseBody != null ? responseBody
                    : JSON.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", "はい、わかりました。")))));
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

    private TutorLlmClient client(String key) {
        return new TutorLlmClient(new TutorLlmProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/",
                key, "deepseek-ai/deepseek-v4.1-flash-1017", "none", 2_000, 5_000), JSON);
    }

    @Test
    void callsTheChatCompletionsEndpointWithItsKeyAndNoReasoning() {
        TutorLlmClient tutor = client("deploy-secret");

        assertThat(tutor.chat("system", List.of(Map.of("role", "user", "content", "前の質問")), "勉強", 700)).isEqualTo("はい、わかりました。");
        assertThat(tutor.model()).isEqualTo("deepseek-ai/deepseek-v4.1-flash-1017");
        Recorded request = requests.getFirst();
        assertThat(request.path()).isEqualTo("/api/v1/chat/completions");
        assertThat(request.authorization()).isEqualTo("Bearer deploy-secret");
        assertThat(request.body().path("max_tokens").asInt()).isEqualTo(700);
        assertThat(request.body().path("stream").asBoolean(true)).isFalse();
        assertThat(request.body().path("reasoning_effort").asText()).isEqualTo("none");
        assertThat(request.body().path("messages")).hasSize(3);
    }

    @Test
    void streamsServerSentEvents() {
        responseBody = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\n\n"
                + ": keep-alive\n\n"
                + "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\",\"content\":\"はい、\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"そうです。\"}}]}\n\n"
                + "data: [DONE]\n\n";
        List<String> deltas = new CopyOnWriteArrayList<>();

        String answer = client("deploy-secret").chatStream("s", List.of(), "u", 50, deltas::add);

        assertThat(answer).isEqualTo("はい、そうです。");
        assertThat(deltas).containsExactly("はい、", "そうです。");
        assertThat(requests.getFirst().body().path("stream").asBoolean()).isTrue();
    }

    @Test
    void providerErrorIsReportedWithoutLeakingTheKey() {
        status = 401;
        responseBody = "{\"error\":{\"message\":\"Authentication Fails, Your api key: ****cret is invalid\"}}";

        assertThatThrownBy(() -> client("deploy-secret").chat("s", List.of(), "u", 50))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR);
                    assertThat(e.getMessage()).doesNotContain("cret");
                });
    }

    @Test
    void retriesOnceWhenTheProviderSendsNothingInTime() {
        TutorLlmClient tutor = new TutorLlmClient(new TutorLlmProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                "k", "m", "none", 1_000, 1_000), JSON);

        hangFirst = 1;
        assertThat(tutor.chat("s", List.of(), "u", 50)).isEqualTo("はい、わかりました。");
        assertThat(requests).hasSize(2);

        // Treo cả hai lượt: báo bận, không thử thêm.
        requests.clear();
        hangFirst = 2;
        assertThatThrownBy(() -> tutor.chat("s", List.of(), "u", 50))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(requests).hasSize(2);
    }

    @Test
    void missingKeyOrUnreachableApiGivesAPlainMessage() {
        assertThatThrownBy(() -> client("").chat("s", List.of(), "u", 50))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(requests).isEmpty();

        TutorLlmClient unreachable = new TutorLlmClient(new TutorLlmProperties("http://127.0.0.1:1/api/v1", "k", "m", "", 1_000, 1_000), JSON);
        assertThatThrownBy(() -> unreachable.chat("s", List.of(), "u", 50))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain("127.0.0.1");
                });
    }
}
