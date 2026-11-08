package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathSpeakResult;
import com.jlas.shadowing.config.AzureSpeechPronunciationConfig;
import com.jlas.shadowing.service.FfmpegService;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jlas.jlptpath.service.JpathClock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * [JLAS-W0108] "Luyện nói theo" scoring (owner 04/11): the learner's recording of one sentence is scored by Azure
 * pronunciation assessment against that sentence. Uses the project's Azure speech settings and ffmpeg; nothing is
 * stored (the recording is deleted after scoring). Owner limit: 30 scored attempts per learner and day; past it the
 * screen keeps recording and playback without a score (JPATH speak limit → TOO_MANY_REQUESTS).
 */
@Service
public class JpathSpeakService {

    static final int DAILY_LIMIT = 30;
    static final int MAX_TEXT = 120;
    private static final Logger log = LoggerFactory.getLogger(JpathSpeakService.class);
    private static final Set<String> MIME = Set.of("audio/webm", "audio/ogg", "audio/wav", "audio/x-wav", "audio/mp4",
            "audio/x-m4a");

    private final AzureSpeechPronunciationConfig config;
    private final FfmpegService ffmpeg;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, Integer> used = new ConcurrentHashMap<>();
    /** E2E-002: the daily limit follows the learner's study day by the app clock (LocalDate.now() only without one). */
    private Function<Long, LocalDate> today = userId -> LocalDate.now();

    public JpathSpeakService(AzureSpeechPronunciationConfig config, FfmpegService ffmpeg, ObjectMapper json) {
        this.config = config;
        this.ffmpeg = ffmpeg;
        this.json = json;
    }

    @Autowired
    public JpathSpeakService(AzureSpeechPronunciationConfig config, FfmpegService ffmpeg, ObjectMapper json,
                             JpathClock clock) {
        this(config, ffmpeg, json);
        this.today = clock::today;
    }

    void setToday(Supplier<LocalDate> today) {
        this.today = userId -> today.get();
    }

    /** Scored attempts left today. */
    public int left(Long userId) {
        return Math.max(0, DAILY_LIMIT - used.getOrDefault(key(userId), 0));
    }

    /** Counts one attempt; false once the day's limit is used up. */
    boolean take(Long userId) {
        String k = key(userId);
        String day = "|" + today.apply(userId);
        used.keySet().removeIf(x -> x.startsWith(userId + "|") && !x.endsWith(day));
        int[] ok = { 0 };
        used.compute(k, (x, n) -> {
            int c = n == null ? 0 : n;
            if (c >= DAILY_LIMIT) return c;
            ok[0] = 1;
            return c + 1;
        });
        return ok[0] == 1;
    }

    private String key(Long userId) {
        return userId + "|" + today.apply(userId);
    }

    public JpathSpeakResult score(Long userId, String text, MultipartFile audio) {
        String reference = text == null ? "" : text.strip();
        if (reference.isEmpty() || reference.length() > MAX_TEXT || !reference.matches("(?s).*[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}].*")) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Câu mẫu không hợp lệ.");
        }
        if (audio == null || audio.isEmpty()) throw new ApiException(ErrorCode.BAD_REQUEST, "Chưa có bản thu để chấm.");
        if (audio.getSize() > config.getMaxAudioBytes()) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Bản thu quá lớn.");
        }
        String mime = audio.getContentType() == null ? "" : audio.getContentType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!MIME.contains(mime)) throw new ApiException(ErrorCode.BAD_REQUEST, "Định dạng bản thu không được hỗ trợ.");
        if (!config.isConfigured()) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Chấm phát âm chưa được cấu hình.");
        }
        if (!take(userId)) {
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS,
                    "Hôm nay bạn đã dùng hết " + DAILY_LIMIT + " lượt chấm phát âm. Bạn vẫn ghi âm và nghe lại được.");
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("jpath-speak-");
            Path src = dir.resolve(UUID.randomUUID() + (mime.contains("ogg") ? ".ogg" : mime.contains("wav") ? ".wav"
                    : mime.contains("mp4") || mime.contains("m4a") ? ".m4a" : ".webm"));
            Path wav = dir.resolve(UUID.randomUUID() + ".wav");
            try (var in = audio.getInputStream()) {
                Files.copy(in, src, StandardCopyOption.REPLACE_EXISTING);
            }
            ffmpeg.convertFileToWav(src.toString(), wav.toString());
            Double seconds = ffmpeg.getDuration(wav.toString());
            if (seconds == null || seconds <= 0 || seconds > config.getMaxAudioSeconds()) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "Bản thu phải có tiếng và không dài quá "
                        + config.getMaxAudioSeconds() + " giây.");
            }
            return parse(json.readTree(callAzure(wav, reference)), left(userId));
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Chưa chấm được phát âm. Thử lại sau.");
        } catch (Exception e) {
            log.warn("JLPT path speak scoring failed: {} {}", e.getClass().getSimpleName(), e.getMessage());
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Chưa chấm được phát âm. Thử lại sau.");
        } finally {
            deleteQuietly(dir);
        }
    }

    private String callAzure(Path wav, String reference) throws IOException, InterruptedException {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ReferenceText", reference);
        p.put("GradingSystem", "HundredMark");
        p.put("Granularity", "Word");
        p.put("Dimension", "Comprehensive");
        p.put("EnableMiscue", true);
        String header = Base64.getEncoder().encodeToString(json.writeValueAsString(p).getBytes(StandardCharsets.UTF_8));
        URI uri = URI.create("https://" + config.getRegion()
                + ".stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1?language="
                + URLEncoder.encode(config.getLocale(), StandardCharsets.UTF_8) + "&format=detailed&profanity=raw");
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45))
                .header("Accept", "application/json")
                .header("Content-Type", "audio/wav; codecs=audio/pcm; samplerate=16000")
                .header("Ocp-Apim-Subscription-Key", config.getSubscriptionKey())
                .header("Pronunciation-Assessment", header)
                .POST(HttpRequest.BodyPublishers.ofFile(wav)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            log.warn("JLPT path speak scoring: Azure HTTP {}", res.statusCode());
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Chưa chấm được phát âm. Thử lại sau.");
        }
        return res.body();
    }

    /** Azure's detailed answer → the learner's score; a recording Azure could not hear is a BAD_REQUEST. */
    static JpathSpeakResult parse(JsonNode root, int left) {
        JsonNode status = root.path("RecognitionStatus");
        String s = status.isNumber() && status.asInt(-1) == 0 ? "Success" : status.asText("Error");
        if (!"Success".equalsIgnoreCase(s)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, switch (s) {
                case "NoMatch" -> "Chưa nghe được tiếng Nhật trong bản thu. Nói to và rõ hơn rồi thử lại.";
                case "InitialSilenceTimeout" -> "Bản thu im lặng quá lâu ở đầu. Bấm ghi rồi nói ngay.";
                case "BabbleTimeout" -> "Bản thu có nhiều tiếng ồn. Thử ở chỗ yên tĩnh hơn.";
                default -> "Chưa nhận diện được bản thu này.";
            });
        }
        JsonNode best = root.path("NBest").path(0);
        JsonNode a = best.path("PronunciationAssessment").isObject() ? best.path("PronunciationAssessment") : best;
        List<JpathSpeakResult.Word> words = new ArrayList<>();
        for (JsonNode w : best.path("Words")) {
            JsonNode wa = w.path("PronunciationAssessment").isObject() ? w.path("PronunciationAssessment") : w;
            words.add(new JpathSpeakResult.Word(w.path("Word").asText(""), num(wa, "AccuracyScore"),
                    wa.path("ErrorType").asText("None")));
        }
        Double pron = num(a, "PronScore");
        return new JpathSpeakResult(pron, num(a, "AccuracyScore"), num(a, "FluencyScore"), num(a, "CompletenessScore"),
                best.path("Display").asText(root.path("DisplayText").asText("")), feedback(pron), words, left);
    }

    private static Double num(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isNumber() ? Math.round(v.asDouble() * 10.0) / 10.0 : null;
    }

    static String feedback(Double score) {
        if (score == null) return "Đã nghe được giọng bạn. Xem các chữ tô đỏ để luyện lại.";
        if (score >= 90) return "Rất tự nhiên. Giữ nhịp và ngữ điệu này.";
        if (score >= 80) return "Tốt. Luyện lại các chữ tô đỏ để câu tự nhiên hơn.";
        if (score >= 70) return "Khá ổn. Nói chậm hơn, chú ý trường âm và âm ngắt.";
        return "Nghe lại câu mẫu, nói từng cụm ngắn rồi ghi lại.";
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (var files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (IOException ignored) {
            // temp files only
        }
    }
}
