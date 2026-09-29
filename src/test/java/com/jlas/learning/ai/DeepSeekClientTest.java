package com.jlas.learning.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** [JLAS-W0108] Mock HTTP cho DeepSeekClient (OpenAI-compatible): parse JSON + repair dấu phẩy thừa. */
class DeepSeekClientTest {

    private static final String URL = "http://deepseek.test/chat/completions";

    private DeepSeekClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        client = new DeepSeekClient();
        ReflectionTestUtils.setField(client, "apiKey", "test-key");
        ReflectionTestUtils.setField(client, "apiUrl", "http://deepseek.test");
        ReflectionTestUtils.setField(client, "model", "test-model");
        ReflectionTestUtils.setField(client, "jsonMaxRetries", 1);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    private void expectContent(String text) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var message = mapper.createObjectNode().put("content", text);
        var choice = mapper.createObjectNode().set("message", message);
        var choices = mapper.createArrayNode().add(choice);
        String body = mapper.createObjectNode().set("choices", choices).toString();
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void parsesJsonStrippingCodeFence() throws Exception {
        expectContent("```json\n{\"overview\":\"oke\",\"phases\":[]}\n```");
        JsonNode node = client.generateJson("sys", "user");
        assertNotNull(node);
        assertEquals("oke", node.get("overview").asText());
    }

    @Test
    void repairsTrailingCommaJson() throws Exception {
        expectContent("{\"a\": 1, \"b\": [1, 2,],}");
        JsonNode node = client.generateJson("sys", "user");
        assertNotNull(node);
        assertEquals(1, node.get("a").asInt());
        assertEquals(2, node.get("b").size());
    }

    @Test
    void returnsNullWithoutApiKey() {
        ReflectionTestUtils.setField(client, "apiKey", "   ");
        assertNull(client.generateJson("sys", "user"));
    }

    @Test
    void returnsNullWhenNoChoices() throws Exception {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));
        assertNull(client.generateJson("sys", "user"));
    }

    @Test
    void returnsNullWhenBodyIsNotJson() throws Exception {
        expectContent("Xin lỗi, tôi không thể trả lời.");
        assertNull(client.generateJson("sys", "user"));
    }
}
