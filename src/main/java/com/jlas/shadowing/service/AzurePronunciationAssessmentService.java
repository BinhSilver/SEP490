package com.jlas.shadowing.service;

import com.jlas.common.ai.AiUsageRecorder;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.auth.security.CurrentUser;
import com.jlas.shadowing.config.AzureSpeechPronunciationConfig;
import com.jlas.shadowing.dto.PronunciationAssessmentDto;
import com.jlas.shadowing.dto.PronunciationPhonemeDto;
import com.jlas.shadowing.dto.PronunciationWordDto;
import com.jlas.shadowing.model.Subtitle;
import com.jlas.shadowing.repository.SubtitleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AzurePronunciationAssessmentService {
    private static final Logger log = LoggerFactory.getLogger(AzurePronunciationAssessmentService.class);
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "audio/webm", "audio/ogg", "audio/wav", "audio/x-wav", "audio/mp4", "audio/x-m4a");
    private static final int MAGIC_BYTES_LENGTH = 12;
    private static final double WEAK_WORD_THRESHOLD = 70.0;

    private final AzureSpeechPronunciationConfig config;
    private final SubtitleRepository subtitleRepository;
    private final VideoService videoService;
    private final FfmpegService ffmpegService;
    private final PronunciationRateLimitService rateLimitService;
    private final PronunciationAttemptService attemptService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AiUsageRecorder usage;

    public AzurePronunciationAssessmentService(
            AzureSpeechPronunciationConfig config,
            SubtitleRepository subtitleRepository,
            VideoService videoService,
            FfmpegService ffmpegService,
            PronunciationRateLimitService rateLimitService,
            PronunciationAttemptService attemptService,
            ObjectMapper objectMapper,
            AiUsageRecorder usage) {
        this.config = config;
        this.subtitleRepository = subtitleRepository;
        this.videoService = videoService;
        this.ffmpegService = ffmpegService;
        this.rateLimitService = rateLimitService;
        this.attemptService = attemptService;
        this.objectMapper = objectMapper;
        this.usage = usage;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public PronunciationAssessmentDto assess(Long subtitleId, MultipartFile audio) {
        Long userId = CurrentUser.requireUserId();
        String diagnosticId = "PRON-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        rateLimitService.check(userId);
        ensureConfigured();
        Subtitle subtitle = subtitleRepository.findById(subtitleId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy câu phụ đề"));
        videoService.requireReadable(subtitle.getVideo());
        videoService.requireAgeAllowed(subtitle.getVideo());
        validateUpload(audio);

        Path tempDirectory = null;
        String stage = "prepare";
        long startedAt = System.nanoTime();
        log.info("Pronunciation assessment started diagnosticId={} userId={} subtitleId={} contentType={} sizeBytes={}",
                diagnosticId, userId, subtitleId, normalizedMime(audio.getContentType()), audio.getSize());
        try {
            stage = "create-temp-directory";
            tempDirectory = Files.createTempDirectory("jlas-pronunciation-");
            Path source = tempDirectory.resolve(UUID.randomUUID() + extensionFor(audio.getContentType()));
            Path wav = tempDirectory.resolve(UUID.randomUUID() + ".wav");
            stage = "write-upload";
            try (var input = audio.getInputStream()) {
                Files.copy(input, source, StandardCopyOption.REPLACE_EXISTING);
            }
            stage = "verify-upload";
            verifyMagicBytes(source, normalizedMime(audio.getContentType()));
            // MediaRecorder WebM commonly reports "Duration: N/A". Decode first so the
            // generated PCM WAV has a reliable duration header.
            stage = "convert-wav";
            // The page converts recordings to 16 kHz mono PCM itself, so the usual upload needs no ffmpeg (a host
            // without it used to fail every check here). Anything else is still converted by ffmpeg.
            double duration = readyWavSeconds(source);
            if (duration > 0) {
                Files.copy(source, wav, StandardCopyOption.REPLACE_EXISTING);
            } else {
                ffmpegService.convertFileToWav(source.toString(), wav.toString());
                stage = "read-duration";
                duration = ffmpegService.getDuration(wav.toString());
            }
            if (duration <= 0 || duration > config.getMaxAudioSeconds()) {
                throw new ApiException(ErrorCode.BAD_REQUEST,
                        "Bản thu phải có âm thanh và không dài quá " + config.getMaxAudioSeconds() + " giây");
            }
            stage = "azure-assessment";
            log.info("Pronunciation assessment calling Azure diagnosticId={} userId={} subtitleId={} durationSec={}",
                    diagnosticId, userId, subtitleId, Math.round(duration * 10.0) / 10.0);
            PronunciationAssessmentDto result = callAzure(wav, subtitle.getText(), diagnosticId);
            // Azure bills pronunciation assessment by audio time.
            usage.record(new AiUsageRecorder.Usage(userId, "PRONUNCIATION_CHECK", "azure", "pronunciation-assessment",
                    null, null, null, null, null, BigDecimal.valueOf(duration).setScale(2, RoundingMode.HALF_UP), null));
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.info("Azure pronunciation completed diagnosticId={} userId={} subtitleId={} durationSec={} elapsedMs={} status={} score={}",
                    diagnosticId, userId, subtitleId, Math.round(duration * 10.0) / 10.0, elapsedMs,
                    result.getRecognitionStatus(), result.getPronunciationScore());
            stage = "persist-attempt";
            PronunciationAssessmentDto saved = attemptService.save(userId, subtitle, audio, duration, result);
            log.info("Pronunciation attempt persisted diagnosticId={} userId={} subtitleId={} attemptId={}",
                    diagnosticId, userId, subtitleId, saved.getAttemptId());
            return saved;
        } catch (ApiException e) {
            log.warn("Pronunciation assessment rejected diagnosticId={} userId={} subtitleId={} stage={} code={} message={}",
                    diagnosticId, userId, subtitleId, stage, e.getErrorCode().name(), e.getMessage());
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Pronunciation assessment interrupted diagnosticId={} userId={} subtitleId={} stage={}",
                    diagnosticId, userId, subtitleId, stage, e);
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Dịch vụ chấm phát âm đang gián đoạn. Mã chẩn đoán: " + diagnosticId);
        } catch (Exception e) {
            log.error("Pronunciation assessment failed diagnosticId={} userId={} subtitleId={} stage={} type={} message={}",
                    diagnosticId, userId, subtitleId, stage, e.getClass().getSimpleName(), e.getMessage(), e);
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Không thể chấm phát âm lúc này. Giai đoạn: " + stage
                            + "; loại lỗi: " + e.getClass().getSimpleName()
                            + "; chi tiết: " + safeDiagnosticDetail(e)
                            + ". Mã chẩn đoán: " + diagnosticId);
        } finally {
            deleteTemporaryDirectory(tempDirectory);
        }
    }

    private PronunciationAssessmentDto callAzure(Path wav, String referenceText, String diagnosticId)
            throws IOException, InterruptedException {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("ReferenceText", referenceText);
        parameters.put("GradingSystem", "HundredMark");
        parameters.put("Granularity", "Phoneme");
        parameters.put("Dimension", "Comprehensive");
        parameters.put("EnableMiscue", true);
        if ("en-US".equalsIgnoreCase(config.getLocale())) {
            parameters.put("EnableProsodyAssessment", true);
        }
        String parameterJson = objectMapper.writeValueAsString(parameters);
        String assessmentHeader = Base64.getEncoder()
                .encodeToString(parameterJson.getBytes(StandardCharsets.UTF_8));
        URI endpoint = URI.create("https://" + config.getRegion()
                + ".stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1"
                + "?language=" + URLEncoder.encode(config.getLocale(), StandardCharsets.UTF_8)
                + "&format=detailed&profanity=raw");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "application/json")
                .header("Content-Type", "audio/wav; codecs=audio/pcm; samplerate=16000")
                .header("Ocp-Apim-Subscription-Key", config.getSubscriptionKey())
                .header("Pronunciation-Assessment", assessmentHeader)
                .POST(HttpRequest.BodyPublishers.ofFile(wav))
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Azure pronunciation rejected diagnosticId={} status={} requestId={} detail={}",
                    diagnosticId, response.statusCode(),
                    response.headers().firstValue("X-RequestId").orElse("n/a"),
                    azureErrorSummary(response.body()));
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Azure Speech từ chối yêu cầu chấm phát âm (HTTP " + response.statusCode()
                            + "). Mã chẩn đoán: " + diagnosticId);
        }
        return parseAzureResponse(objectMapper.readTree(response.body()), referenceText);
    }

    private String azureErrorSummary(String body) {
        if (body == null || body.isBlank()) return "empty-response";
        try {
            JsonNode root = objectMapper.readTree(body);
            String code = root.path("error").path("code").asText("");
            String message = root.path("error").path("message").asText("");
            if (message.isBlank()) message = root.path("message").asText("");
            String summary = (code + " " + message).trim();
            return truncateLog(summary.isBlank() ? "unrecognized-error-json" : summary);
        } catch (Exception ignored) {
            return truncateLog(body);
        }
    }

    private static String truncateLog(String value) {
        String sanitized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return sanitized.length() <= 300 ? sanitized : sanitized.substring(0, 300) + "...";
    }

    private static String safeDiagnosticDetail(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "không có thông tin bổ sung";
        return truncateLog(message);
    }

    PronunciationAssessmentDto parseAzureResponse(JsonNode root, String referenceText) {
        JsonNode statusNode = root.path("RecognitionStatus");
        String status = statusNode.isNumber() && statusNode.asInt(-1) == 0
                ? "Success"
                : statusNode.asText("Error");
        if (!"Success".equalsIgnoreCase(status)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, recognitionMessage(status));
        }
        JsonNode best = root.path("NBest").path(0);
        if (best.isMissingNode()) {
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Azure Speech không trả về kết quả chi tiết");
        }
        PronunciationAssessmentDto dto = new PronunciationAssessmentDto();
        dto.setRecognitionStatus(status);
        dto.setReferenceText(referenceText);
        dto.setRecognizedText(text(best, "Display", root.path("DisplayText").asText("")));
        JsonNode overallAssessment = assessment(best);
        dto.setPronunciationScore(number(overallAssessment, "PronScore"));
        dto.setAccuracyScore(number(overallAssessment, "AccuracyScore"));
        dto.setFluencyScore(number(overallAssessment, "FluencyScore"));
        dto.setCompletenessScore(number(overallAssessment, "CompletenessScore"));
        dto.setProsodyScore(number(overallAssessment, "ProsodyScore"));
        dto.setFeedback(feedback(dto.getPronunciationScore()));
        for (JsonNode wordNode : best.path("Words")) {
            JsonNode wordAssessment = assessment(wordNode);
            PronunciationWordDto word = new PronunciationWordDto();
            word.setWord(wordNode.path("Word").asText(""));
            word.setAccuracyScore(number(wordAssessment, "AccuracyScore"));
            word.setErrorType(wordAssessment.path("ErrorType").asText("None"));
            for (JsonNode phoneme : wordNode.path("Phonemes")) {
                JsonNode phonemeAssessment = assessment(phoneme);
                word.getPhonemes().add(new PronunciationPhonemeDto(
                        phoneme.path("Phoneme").asText(""), number(phonemeAssessment, "AccuracyScore")));
            }
            dto.getWords().add(word);
        }
        return dto;
    }

    private void validateUpload(MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Chưa có bản thu để chấm");
        }
        if (audio.getSize() > config.getMaxAudioBytes()) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Bản thu vượt quá giới hạn 5 MB");
        }
        if (!ALLOWED_MIME_TYPES.contains(normalizedMime(audio.getContentType()))) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Chỉ hỗ trợ audio WebM, OGG, WAV hoặc M4A/MP4");
        }
    }

    private static void verifyMagicBytes(Path source, String mime) throws IOException {
        byte[] header = new byte[MAGIC_BYTES_LENGTH];
        int read;
        try (var input = Files.newInputStream(source)) {
            read = input.read(header);
        }
        boolean valid = read >= 4 && switch (mime) {
            case "audio/webm" -> header[0] == 0x1A && header[1] == 0x45
                    && (header[2] & 0xFF) == 0xDF && (header[3] & 0xFF) == 0xA3;
            case "audio/ogg" -> header[0] == 'O' && header[1] == 'g' && header[2] == 'g' && header[3] == 'S';
            case "audio/wav", "audio/x-wav" -> header[0] == 'R' && header[1] == 'I'
                    && header[2] == 'F' && header[3] == 'F';
            case "audio/mp4", "audio/x-m4a" -> read >= 8 && header[4] == 'f' && header[5] == 't'
                    && header[6] == 'y' && header[7] == 'p';
            default -> false;
        };
        if (!valid) throw new ApiException(ErrorCode.BAD_REQUEST, "Nội dung file audio không hợp lệ");
    }

    /**
     * Seconds of audio in a 16 kHz, mono, 16-bit PCM WAV, the format Azure is sent; -1 for any other file.
     * Walks the RIFF chunks instead of assuming a 44-byte header, since encoders may add chunks before "data".
     */
    static double readyWavSeconds(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        if (data.length < 44 || !ascii(data, 0, "RIFF") || !ascii(data, 8, "WAVE")) return -1;
        ByteBuffer bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        boolean pcm16kMono = false;
        int position = 12;
        while (position + 8 <= data.length) {
            int size = bytes.getInt(position + 4);
            if (size < 0) return -1;
            if (ascii(data, position, "fmt ") && size >= 16 && position + 24 <= data.length) {
                pcm16kMono = (bytes.getShort(position + 8) & 0xffff) == 1          // PCM
                        && (bytes.getShort(position + 10) & 0xffff) == 1          // mono
                        && bytes.getInt(position + 12) == 16_000
                        && (bytes.getShort(position + 22) & 0xffff) == 16;
            } else if (ascii(data, position, "data")) {
                if (!pcm16kMono) return -1;
                long audioBytes = Math.min(size, data.length - position - 8L);
                return audioBytes > 0 ? audioBytes / 32_000.0 : -1;
            }
            position += 8 + size + (size & 1);
        }
        return -1;
    }

    private static boolean ascii(byte[] data, int offset, String text) {
        return offset + text.length() <= data.length
                && new String(data, offset, text.length(), StandardCharsets.US_ASCII).equals(text);
    }

    private static String normalizedMime(String contentType) {
        if (contentType == null) return "";
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static String extensionFor(String contentType) {
        return switch (normalizedMime(contentType)) {
            case "audio/ogg" -> ".ogg";
            case "audio/wav", "audio/x-wav" -> ".wav";
            case "audio/mp4", "audio/x-m4a" -> ".m4a";
            default -> ".webm";
        };
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? Math.round(value.asDouble() * 10.0) / 10.0 : null;
    }

    private static JsonNode assessment(JsonNode node) {
        JsonNode nested = node.path("PronunciationAssessment");
        return nested.isObject() ? nested : node;
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    private static String feedback(Double score) {
        if (score == null) return "Đã nhận diện giọng đọc. Hãy xem từng từ để luyện lại.";
        if (score >= 90) return "Phát âm rất tự nhiên. Hãy duy trì nhịp và ngữ điệu này.";
        if (score >= 80) return "Phát âm tốt. Luyện lại những từ được đánh dấu để câu tự nhiên hơn.";
        if (score >= WEAK_WORD_THRESHOLD) return "Khá ổn. Hãy đọc chậm hơn và chú ý trường độ từng âm.";
        return "Hãy nghe lại câu mẫu, đọc từng cụm ngắn rồi thu lại.";
    }

    private static String recognitionMessage(String status) {
        return switch (status) {
            case "NoMatch" -> "Không nhận diện được tiếng Nhật trong bản thu";
            case "InitialSilenceTimeout" -> "Bản thu bắt đầu bằng khoảng lặng quá dài";
            case "BabbleTimeout" -> "Bản thu có quá nhiều tiếng ồn";
            default -> "Azure Speech không thể nhận diện bản thu này";
        };
    }

    private void ensureConfigured() {
        if (!config.isConfigured()) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Chưa cấu hình AZURE_SPEECH_KEY và AZURE_SPEECH_REGION");
        }
    }

    private static void deleteTemporaryDirectory(Path directory) {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
