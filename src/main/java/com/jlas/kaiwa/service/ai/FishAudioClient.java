package com.jlas.kaiwa.service.ai;

import com.jlas.kaiwa.config.FishAudioConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * Fish Audio TTS client — POST https://api.fish.audio/v1/tts
 * Docs: https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech
 */
@Component
public class FishAudioClient {

    private static final Logger log = LoggerFactory.getLogger(FishAudioClient.class);
    private static final int STREAM_BUFFER_SIZE = 16 * 1024;

    private final FishAudioConfig config;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public FishAudioClient(FishAudioConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);
        factory.setReadTimeout(90_000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * @param text   Japanese (or other) text to speak
     * @param gender "female" or "male" — maps to reference_id in application.properties
     * @param speed  prosody speed 0.5–2.0
     * @return mp3 bytes
     */
    public byte[] synthesize(String text, String gender, double speed) {
        return synthesizeVoice(text, config.resolveVoiceId(gender), speed);
    }

    public byte[] synthesizeVoice(String text, String referenceId, double speed) {
        TtsRequest request = buildRequest(text, referenceId, speed);

        String url = trimSlash(config.getApiUrl()) + "/v1/tts";
        HttpHeaders headers = createHeaders(request.apiKey());

        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(request.body(), headers),
                    byte[].class);

            byte[] audio = response.getBody();
            if (audio == null || audio.length == 0) {
                throw new IllegalStateException("Fish Audio returned empty audio");
            }
            log.info("Fish Audio TTS OK ref={} bytes={} model={}",
                    request.referenceId(), audio.length, config.getModel());
            return audio;
        } catch (HttpStatusCodeException e) {
            String errBody = e.getResponseBodyAsString();
            log.error("Fish Audio HTTP {}: {}", e.getStatusCode(), errBody);
            throw new IllegalStateException(
                    "Fish Audio HTTP " + e.getStatusCode().value() + ": "
                            + (errBody != null && !errBody.isBlank() ? errBody : e.getMessage()),
                    e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException || e instanceof IllegalArgumentException) {
                throw e;
            }
            log.error("Fish Audio call failed: {}", e.getMessage(), e);
            throw new IllegalStateException("Fish Audio call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Proxies Fish Audio's single progressive MP3 response directly to the caller.
     * No sentence splitting or intermediate byte[] is used, so playback can start
     * as soon as the provider emits its first audio frames.
     */
    public void synthesizeStreaming(String text, String gender, double speed, OutputStream target) {
        synthesizeVoiceStreaming(text, config.resolveVoiceId(gender), speed, target);
    }

    public void synthesizeVoiceStreaming(String text, String referenceId, double speed, OutputStream target) {
        TtsRequest request = buildRequest(text, referenceId, speed);
        String url = trimSlash(config.getApiUrl()) + "/v1/tts";
        HttpHeaders headers = createHeaders(request.apiKey());
        long startedAt = System.nanoTime();

        try {
            StreamStats stats = restTemplate.execute(
                    url,
                    HttpMethod.POST,
                    clientRequest -> {
                        clientRequest.getHeaders().putAll(headers);
                        clientRequest.getBody().write(objectMapper.writeValueAsBytes(request.body()));
                    },
                    response -> copyProgressively(response.getBody(), target, startedAt));

            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("Fish Audio streaming TTS OK ref={} bytes={} firstByteMs={} elapsedMs={} model={}",
                    request.referenceId(),
                    stats == null ? 0 : stats.bytes(),
                    stats == null ? -1 : stats.firstByteMs(),
                    elapsedMs,
                    config.getModel());
        } catch (HttpStatusCodeException e) {
            String errBody = e.getResponseBodyAsString();
            log.error("Fish Audio streaming HTTP {}: {}", e.getStatusCode(), errBody);
            throw new IllegalStateException(
                    "Fish Audio HTTP " + e.getStatusCode().value() + ": "
                            + (errBody != null && !errBody.isBlank() ? errBody : e.getMessage()),
                    e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException || e instanceof IllegalArgumentException) {
                throw e;
            }
            log.error("Fish Audio streaming call failed: {}", e.getMessage(), e);
            throw new IllegalStateException("Fish Audio streaming call failed: " + e.getMessage(), e);
        }
    }

    private TtsRequest buildRequest(String text, String referenceId, double speed) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("YOUR_")) {
            throw new IllegalStateException("fish.audio.api.key is not set in application.properties");
        }
        if (referenceId == null || referenceId.isBlank() || referenceId.startsWith("YOUR_")) {
            throw new IllegalStateException("Chưa cấu hình giọng đọc cho nhân vật này.");
        }

        double spd = Math.min(2.0, Math.max(0.5, speed));
        Map<String, Object> prosody = new HashMap<>();
        prosody.put("speed", spd);
        prosody.put("volume", 0);

        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        body.put("reference_id", referenceId);
        body.put("format", "mp3");
        body.put("mp3_bitrate", 128);
        // temperature mặc định (0.7) làm nhịp và ngữ điệu lệch giữa các câu; cấu hình này được chọn sau khi nghe so sánh.
        // "balanced" gửi âm thanh dần (~0,4 giây tới byte đầu); "normal" chỉ gửi khi tạo xong (3,7–6 giây).
        body.put("latency", "balanced");
        body.put("temperature", 0.2);
        body.put("top_p", 0.7);
        body.put("prosody", prosody);
        return new TtsRequest(apiKey.trim(), referenceId, body);
    }

    private HttpHeaders createHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        headers.set("model", config.getModel() != null ? config.getModel().trim() : "s2.1-pro-free");
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL));
        return headers;
    }

    private static StreamStats copyProgressively(InputStream source, OutputStream target, long startedAt)
            throws IOException {
        byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        long total = 0;
        long firstByteMs = -1;
        int read;
        while ((read = source.read(buffer)) != -1) {
            if (firstByteMs < 0) {
                firstByteMs = (System.nanoTime() - startedAt) / 1_000_000;
            }
            target.write(buffer, 0, read);
            target.flush();
            total += read;
        }
        return new StreamStats(total, firstByteMs);
    }

    private record TtsRequest(String apiKey, String referenceId, Map<String, Object> body) {
    }

    private record StreamStats(long bytes, long firstByteMs) {
    }

    private static String trimSlash(String base) {
        if (base == null || base.isBlank()) {
            return "https://api.fish.audio";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
