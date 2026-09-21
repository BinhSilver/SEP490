package com.jlas.shadowing.service;

import com.jlas.shadowing.dto.SttResult;
import com.jlas.shadowing.dto.SubtitleDto;
import com.jlas.shadowing.util.NullSafe;
import com.jlas.shadowing.util.SubtitleText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
@Service
public class DeepgramService {

    private static final String LISTEN_URL =
            "https://api.deepgram.com/v1/listen?model=nova-2&language=ja&punctuate=true&utterances=true";

    @Value("${deepgram.api.key:}")
    private String apiKey;

    @Value("${deepgram.upload.max-retries:5}")
    private int maxRetries;

    @Value("${deepgram.upload.delay-between-ms:2000}")
    private int delayBetweenMs;

    @Value("${deepgram.upload.split.min-seconds:60}")
    private int splitMinSeconds;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private FfmpegService ffmpegService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<SubtitleDto> transcribe(String audioFilePath) throws Exception {
        File file = new File(audioFilePath);
        if (!file.exists()) {
            throw new IllegalArgumentException("Audio file not found: " + audioFilePath);
        }

        double duration = ffmpegService.getDuration(audioFilePath);
        if (duration > splitMinSeconds) {
            System.out.println("Deepgram: audio " + file.getName()
                    + " size=" + (file.length() / 1024) + "KB duration=" + duration + "s"
                    + " — chia 2 nửa, gửi 2 lần");
            return transcribeInHalves(file, duration);
        }

        System.out.println("Deepgram: audio " + file.getName()
                + " size=" + (file.length() / 1024) + "KB duration=" + duration + "s"
                + " — gửi 1 lần");
        return parseUtterances(postAudio(file));
    }

    public String transcribeMultipart(MultipartFile file) throws Exception {
        String contentType = file.getContentType() != null ? file.getContentType() : "audio/webm";
        return transcribeBytes(file.getBytes(), contentType);
    }

    public String transcribeBytes(byte[] bytes, String contentType) throws Exception {
        return transcribeBytesWithConfidence(bytes, contentType, 0).getTranscript();
    }

    public SttResult transcribeWavFile(File wavFile, double minConfidence) throws Exception {
        String json = postAudio(wavFile);
        return parseSttResult(json, minConfidence);
    }

    public SttResult transcribeBytesWithConfidence(byte[] bytes, String contentType, double minConfidence)
            throws Exception {
        String json = postAudioBytes(bytes, contentType);
        return parseSttResult(json, minConfidence);
    }

    private SttResult parseSttResult(String json, double minConfidence) throws Exception {
        SttResult result = new SttResult();
        JsonNode root = objectMapper.readTree(json);
        JsonNode alt = root.path("results").path("channels").path(0).path("alternatives").path(0);

        String transcript = alt.path("transcript").asText("").trim();
        if (transcript.isBlank()) {
            List<SubtitleDto> subs = parseUtterances(json);
            if (!subs.isEmpty()) {
                transcript = subs.stream()
                        .map(dto -> dto.getText())
                        .filter(t -> t != null && !t.isBlank())
                        .reduce((a, b) -> a + " " + b)
                        .orElse("")
                        .trim();
            }
        }

        double confidence = alt.path("confidence").asDouble(0);
        result.setTranscript(transcript);
        result.setConfidence(confidence);

        if (transcript.isBlank()) {
            result.setReliable(false);
            result.setFailureReason("STT không trả về văn bản");
        } else if (confidence < minConfidence) {
            result.setReliable(false);
            result.setFailureReason(String.format(
                    "Độ tin cậy STT thấp (%.0f%% < %.0f%%)",
                    confidence * 100, minConfidence * 100));
        } else {
            result.setReliable(true);
        }
        return result;
    }

    private List<SubtitleDto> transcribeInHalves(File file, double totalDuration) throws Exception {
        List<FfmpegService.AudioSegment> segments = ffmpegService.splitAudioInHalf(file.getAbsolutePath());
        List<SubtitleDto> all = new ArrayList<>();
        List<String> tempPaths = new ArrayList<>();

        try {
            for (int i = 0; i < segments.size(); i++) {
                if (i > 0 && delayBetweenMs > 0) {
                    Thread.sleep(delayBetweenMs);
                }

                FfmpegService.AudioSegment segment = segments.get(i);
                tempPaths.add(segment.getPath());
                File chunk = new File(segment.getPath());
                double offset = segment.getStartOffset();

                System.out.println("Deepgram phần " + (i + 1) + "/2"
                        + " offset=" + String.format("%.1f", offset) + "s"
                        + " size=" + (chunk.length() / 1024) + "KB"
                        + " (~" + String.format("%.0f", totalDuration / 2) + "s audio)");

                String json = postAudio(chunk);
                all.addAll(offsetSubtitles(parseUtterances(json), offset));
            }
        } finally {
            ffmpegService.deleteFiles(tempPaths);
        }

        all.sort(Comparator.comparingDouble(dto -> dto.getStartTime() != null ? dto.getStartTime() : 0.0));
        return all;
    }

    private String postAudio(File file) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        return postAudioBytes(bytes, resolveContentType(file.getName()).toString());
    }

    private String postAudioBytes(byte[] audioBytes, String contentType) throws Exception {
        if (apiKey == null || apiKey.isBlank() || "YOUR_DEEPGRAM_API_KEY".equals(apiKey.trim())) {
            throw new IllegalStateException(
                    "Chưa cấu hình deepgram.api.key. Lấy key tại https://console.deepgram.com và "
                            + "điền vào src/main/resources/application.properties");
        }
        String token = apiKey.trim();
        int delayMs = 3000;
        Exception lastError = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set("Authorization", "Token " + token);
                headers.setContentType(MediaType.parseMediaType(NullSafe.str(contentType)));
                headers.setContentLength(audioBytes.length);

                HttpEntity<byte[]> entity = new HttpEntity<>(audioBytes, headers);
                ResponseEntity<String> response = restTemplate.postForEntity(LISTEN_URL, entity, String.class);

                if (response.getStatusCode() == HttpStatus.OK) {
                    return response.getBody();
                }
                throw new RuntimeException("Deepgram API failed: " + response.getBody());
            } catch (ResourceAccessException e) {
                lastError = e;
                if (attempt < maxRetries) {
                    System.err.println("Deepgram connection timeout (attempt " + attempt + "/" + maxRetries
                            + "): " + e.getMessage());
                    Thread.sleep(delayMs);
                    delayMs = Math.min(delayMs * 2, 30_000);
                }
            } catch (HttpStatusCodeException e) {
                lastError = e;
                int code = e.getStatusCode().value();
                boolean retryable = code == 408 || code == 429 || code == 503 || code == 504;
                if (retryable && attempt < maxRetries) {
                    System.err.println("Deepgram HTTP " + code + " (attempt " + attempt + "/" + maxRetries + ")");
                    Thread.sleep(delayMs);
                    delayMs = Math.min(delayMs * 2, 30_000);
                } else {
                    throw new RuntimeException(formatDeepgramHttpError(code, e.getResponseBodyAsString()), e);
                }
            }
        }

        throw new RuntimeException(
                "Không kết nối được Deepgram (api.deepgram.com). "
                        + "Kiểm tra internet, VPN, firewall/antivirus chặn Java. Chi tiết: "
                        + (lastError != null ? lastError.getMessage() : "unknown"),
                lastError);
    }

    private String formatDeepgramHttpError(int code, String body) {
        String detail = body != null ? body.trim() : "";
        if (code == 401) {
            return "Deepgram 401 Unauthorized — API key sai, hết hạn hoặc đã bị revoke. "
                    + "Cập nhật deepgram.api.key trong application.properties "
                    + "(console: https://console.deepgram.com  API Keys). "
                    + (detail.isEmpty() ? "" : "Chi tiết: " + detail);
        }
        if (code == 402 || code == 403) {
            return "Deepgram " + code + " — hết credit hoặc key không đủ quyền. "
                    + "Kiểm tra billing/project trên console.deepgram.com. "
                    + (detail.isEmpty() ? "" : "Chi tiết: " + detail);
        }
        return "Deepgram failed (" + code + "): " + (detail.isEmpty() ? "(no body)" : detail);
    }

    private MediaType resolveContentType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp3")) return MediaType.parseMediaType("audio/mpeg");
        if (lower.endsWith(".wav")) return MediaType.parseMediaType("audio/wav");
        if (lower.endsWith(".webm")) return MediaType.parseMediaType("audio/webm");
        if (lower.endsWith(".ogg")) return MediaType.parseMediaType("audio/ogg");
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private List<SubtitleDto> offsetSubtitles(List<SubtitleDto> subs, double offset) {
        if (offset == 0) return subs;
        for (SubtitleDto dto : subs) {
            dto.setStartTime(dto.getStartTime() + offset);
            dto.setEndTime(dto.getEndTime() + offset);
        }
        return subs;
    }

    private List<SubtitleDto> parseUtterances(String jsonResponse) throws Exception {
        List<SubtitleDto> subtitles = new ArrayList<>();
        JsonNode root = objectMapper.readTree(jsonResponse);
        JsonNode utterances = root.path("results").path("utterances");

        if (utterances.isArray()) {
            for (JsonNode node : utterances) {
                String transcript = SubtitleText.normalizeJapanese(node.path("transcript").asText());
                if (transcript.isBlank()) {
                    continue; // skip lone "。" / empty cues
                }
                SubtitleDto dto = new SubtitleDto();
                dto.setText(transcript);
                dto.setStartTime(node.path("start").asDouble());
                dto.setEndTime(node.path("end").asDouble());
                subtitles.add(dto);
            }
        }
        return subtitles;
    }
}
