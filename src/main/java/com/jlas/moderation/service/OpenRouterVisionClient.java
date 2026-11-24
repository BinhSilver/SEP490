package com.jlas.moderation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Gọi OpenRouter chat/completions với image (base64).
 * Model lấy từ {@code moderation.image.model} — để trống = không gọi.
 */
@Component
@Slf4j
public class OpenRouterVisionClient {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jlas.common.ai.AiUsageRecorder usageLog;

    @Value("${openrouter.api.key:}")
    private String apiKey;

    @Value("${openrouter.api.url:https://openrouter.ai/api/v1}")
    private String apiUrl;

    @Value("${openrouter.site.url:http://localhost:8080}")
    private String siteUrl;

    @Value("${openrouter.site.name:JLAS}")
    private String siteName;

    @Value("${moderation.image.model:}")
    private String primaryModel;

    @Value("${moderation.image.fallback-model:}")
    private String fallbackModel;

    @Value("${moderation.image.max-tokens:256}")
    private int maxTokens;

    @Value("${moderation.image.timeout-ms:45000}")
    private int timeoutMs;

    public OpenRouterVisionClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restTemplate = buildRestTemplate(45_000);
    }

    public boolean isConfigured() {
        return StringUtils.hasText(apiKey) && StringUtils.hasText(primaryModel);
    }

    public String getPrimaryModel() {
        return primaryModel != null ? primaryModel.trim() : "";
    }

    /**
     * @return raw model text (expect JSON)
     */
    public String analyzeImage(Path imagePath, String userPrompt) throws Exception {
        return analyzeImage(imagePath, userPrompt, IMAGE_SYSTEM_PROMPT);
    }

    /** Như {@link #analyzeImage(Path, String)} nhưng với system prompt riêng (vd. ảnh bài viết Cộng đồng). */
    public String analyzeImage(Path imagePath, String userPrompt, String systemPrompt) throws Exception {
        return completeWithFallback(systemPrompt, imageContent(imagePath, userPrompt));
    }

    /** Có model dự phòng để hỏi ý kiến thứ hai không ({@code moderation.image.fallback-model}). */
    public boolean hasFallbackModel() {
        return isConfigured() && StringUtils.hasText(fallbackModel);
    }

    public String getFallbackModel() {
        return fallbackModel != null ? fallbackModel.trim() : "";
    }

    /** Hỏi riêng model dự phòng (ý kiến thứ hai khi model chính chưa chắc chắn). */
    public String analyzeImageWithFallbackModel(Path imagePath, String userPrompt, String systemPrompt) throws Exception {
        if (!hasFallbackModel()) {
            throw new IllegalStateException("moderation.image.fallback-model chưa cấu hình");
        }
        return complete(fallbackModel.trim(), systemPrompt, imageContent(imagePath, userPrompt));
    }

    private List<Map<String, Object>> imageContent(Path imagePath, String userPrompt) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("moderation.image.model chưa cấu hình");
        }
        byte[] bytes = Files.readAllBytes(imagePath);
        String mime = mimeOf(imagePath);
        String b64 = Base64.getEncoder().encodeToString(bytes);
        String dataUrl = "data:" + mime + ";base64," + b64;

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", userPrompt));
        content.add(Map.of(
                "type", "image_url",
                "image_url", Map.of("url", dataUrl)
        ));
        return content;
    }

    /** Phân loại một đoạn chữ (không kèm ảnh) bằng cùng model kiểm duyệt; trả về chữ thô của model (mong đợi JSON). */
    public String analyzeText(String systemPrompt, String userText) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("moderation.image.model chưa cấu hình");
        }
        return completeWithFallback(systemPrompt, userText);
    }

    private String completeWithFallback(String systemPrompt, Object userContent) throws Exception {
        try {
            return complete(primaryModel.trim(), systemPrompt, userContent);
        } catch (Exception e) {
            if (StringUtils.hasText(fallbackModel)) {
                log.warn("Vision primary failed ({}), trying fallback {}", e.getMessage(), fallbackModel);
                return complete(fallbackModel.trim(), systemPrompt, userContent);
            }
            throw e;
        }
    }

    private String complete(String model, String systemPrompt, Object userContent) throws Exception {
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userContent);

        Map<String, Object> systemMsg = Map.of("role", "system", "content", systemPrompt);

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", List.of(systemMsg, userMsg));
        body.put("temperature", 0);
        body.put("max_tokens", maxTokens);
        return send(model, body);
    }

    private static final String IMAGE_SYSTEM_PROMPT = """
                        You are a content moderator for KotsuKotsu, a Japanese learning community (not a large social network).
                        Flag policy risks in the IMAGE:
                        - sexual/NSFW, nudity level, pornography, sexualized minors / grooming cues
                        - graphic gore, extreme violence
                        - hate symbols / extremism
                        - illegal drugs prominently displayed
                        - self-harm / suicide instructional imagery
                        - weapons displayed in a threatening or glorifying way
                        Educational anime/manga, classrooms, JLPT materials, non-sexual character art  usually SAFE.
                        Ambiguous cases  REVIEW (prefer REVIEW over BLOCK when unsure).
                        Reply JSON only, no markdown:
                        {"verdict":"SAFE"|"REVIEW"|"BLOCKED","categories":["..."],"confidence":0.0,"risk":0,"reason":"short","summary":"short"}
                        risk is 0-100 integer.
                        """;

    private String send(String model, Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(com.jlas.common.ai.AiFeature.MODERATION_IMAGE.apiKey(apiKey));
        headers.set("HTTP-Referer", siteUrl);
        headers.set("X-Title", siteName);

        RestTemplate rt = timeoutMs != 45_000 ? buildRestTemplate(timeoutMs) : restTemplate;
        ResponseEntity<String> resp = rt.exchange(
                apiUrl.replaceAll("/$", "") + "/chat/completions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );
        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            throw new IllegalStateException("OpenRouter vision HTTP " + resp.getStatusCode());
        }
        if (usageLog != null) usageLog.recordOpenRouter(com.jlas.common.ai.AiFeature.MODERATION_IMAGE, model, resp.getBody());
        JsonNode root = objectMapper.readTree(resp.getBody());
        if (root.has("error")) {
            throw new IllegalStateException("OpenRouter vision error: " + root.get("error"));
        }
        JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
        if (contentNode.isMissingNode() || contentNode.asText().isBlank()) {
            throw new IllegalStateException("OpenRouter vision empty content model=" + model);
        }
        return contentNode.asText().trim();
    }

    private static String mimeOf(Path path) {
        String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    private static RestTemplate buildRestTemplate(int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);
        factory.setReadTimeout(Math.max(5_000, readTimeoutMs));
        RestTemplate rt = new RestTemplate(factory);
        rt.getMessageConverters().removeIf(c ->
                c instanceof org.springframework.http.converter.StringHttpMessageConverter);
        rt.getMessageConverters().add(0,
                new org.springframework.http.converter.StringHttpMessageConverter(StandardCharsets.UTF_8));
        return rt;
    }
}
