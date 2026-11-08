package com.jlas.dictionary.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class AzureTtsService {
    private static final Logger log = LoggerFactory.getLogger(AzureTtsService.class);
    private static final Pattern JAPANESE = Pattern.compile("[\\p{sc=Hiragana}\\p{sc=Katakana}\\p{sc=Han}]");
    private static final Pattern REGION = Pattern.compile("[a-z0-9-]{2,32}");
    private static final Pattern VOICE = Pattern.compile("[A-Za-z0-9-]+");
    private static final long WINDOW_MILLIS = 60_000;

    private final String key;
    private final String region;
    private final String voice;
    private final String outputFormat;
    private final Path cacheDir;
    private final int maxChars;
    private final int requestsPerMinute;
    private final HttpClient http;
    private final Clock clock;
    private final Map<String, Deque<Long>> windows = new HashMap<>();

    @Autowired
    public AzureTtsService(
            @Value("${azure.speech.tts.key:}") String key,
            @Value("${azure.speech.tts.region:}") String region,
            @Value("${azure.speech.tts.voice:ja-JP-NanamiNeural}") String voice,
            @Value("${azure.speech.tts.output-format:audio-24khz-48kbitrate-mono-mp3}") String outputFormat,
            @Value("${dictionary.tts.cache-dir:uploads/tts-cache}") String cacheDir,
            @Value("${dictionary.tts.max-chars:200}") int maxChars,
            @Value("${dictionary.tts.requests-per-minute:30}") int requestsPerMinute) {
        this(key, region, voice, outputFormat, Path.of(cacheDir), maxChars, requestsPerMinute,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Clock.systemUTC());
    }

    AzureTtsService(String key, String region, String voice, String outputFormat, Path cacheDir,
                    int maxChars, int requestsPerMinute, HttpClient http, Clock clock) {
        this.key = key == null ? "" : key.trim();
        this.region = region == null ? "" : region.trim();
        this.voice = voice;
        this.outputFormat = outputFormat;
        this.cacheDir = cacheDir;
        this.maxChars = maxChars;
        this.requestsPerMinute = requestsPerMinute;
        this.http = http;
        this.clock = clock;
    }

    /** Không khóa cả hàm: lời gọi Azure có thể mất vài giây, chỉ phần đếm lượt mới cần đồng bộ. */
    public byte[] synthesize(String rawText, String clientKey) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > maxChars || !JAPANESE.matcher(text).find()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Vui lòng nhập văn bản tiếng Nhật hợp lệ");
        }
        if (key.isBlank() || !REGION.matcher(region).matches() || !VOICE.matcher(voice).matches()) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Chưa cấu hình dịch vụ phát âm");
        }

        Path cached = cacheDir.resolve(sha256(voice + "|" + outputFormat + "|" + text) + ".mp3");
        try {
            if (Files.isRegularFile(cached) && Files.size(cached) > 0) return Files.readAllBytes(cached);
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Không đọc được bộ nhớ đệm phát âm");
        }
        checkRateLimit(clientKey);
        String ssml = "<speak version=\"1.0\" xml:lang=\"ja-JP\"><voice name=\"" + voice +
                "\">" + escapeXml(text) + "</voice></speak>";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://" + region + ".tts.speech.microsoft.com/cognitiveservices/v1"))
                .timeout(Duration.ofSeconds(20))
                .header("Ocp-Apim-Subscription-Key", key)
                .header("Content-Type", "application/ssml+xml")
                .header("X-Microsoft-OutputFormat", outputFormat)
                .header("User-Agent", "JLAS-Dictionary")
                .POST(HttpRequest.BodyPublishers.ofString(ssml, StandardCharsets.UTF_8))
                .build();
        byte[] audio;
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
                log.warn("Azure TTS status {}", response.statusCode());
                throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Không phát được âm thanh");
            }
            audio = response.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Không phát được âm thanh");
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Không phát được âm thanh");
        }
        try {
            Files.createDirectories(cacheDir);
            Path temporary = Files.createTempFile(cacheDir, "tts-", ".tmp");
            try {
                Files.write(temporary, audio);
                try {
                    Files.move(temporary, cached, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Không lưu được bộ nhớ đệm phát âm");
        }
        return audio;
    }

    private synchronized void checkRateLimit(String clientKey) {
        if (requestsPerMinute <= 0) return;
        long now = clock.millis();
        // Dọn các client đã hết cửa sổ để map không phình theo số IP.
        windows.values().removeIf(old -> !old.isEmpty() && now - old.peekLast() >= WINDOW_MILLIS);
        Deque<Long> times = windows.computeIfAbsent(clientKey, ignored -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MILLIS) times.removeFirst();
        if (times.size() >= requestsPerMinute) {
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS, "Bạn phát âm quá nhanh. Vui lòng thử lại sau.");
        }
        times.addLast(now);
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
