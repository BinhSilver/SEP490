package com.jlas.shadowing.service;

import com.jlas.shadowing.dto.YoutubeAnalyzeResultDto;
import com.jlas.shadowing.util.youtube.TranslationTrack;
import com.jlas.shadowing.util.youtube.YoutubeUrlValidator;

import java.util.Locale;
import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class YoutubeAnalyzeService {

    @Autowired private YoutubeService youtubeService;
    @Autowired private ShadowingTakedownService takedownService;

    @Value("${youtube.max.duration.no-subtitle.seconds:300}")
    private int maxDurationNoSubtitleSeconds;

    @Value("${processing.ai.max.duration.seconds:1200}")
    private int aiMaxDurationSeconds;

    public YoutubeAnalyzeResultDto analyze(String rawUrl) throws Exception {
        YoutubeUrlValidator.requireValid(rawUrl);
        String normalized = YoutubeUrlValidator.normalizeWatchUrl(rawUrl);
        String videoId = YoutubeUrlValidator.extractVideoId(rawUrl).orElseThrow();
        if (takedownService.isBlockedYoutube(videoId)) {
            throw new IllegalArgumentException("Video này đã bị gỡ vì vi phạm bản quyền hoặc quy định cộng đồng "
                    + "nên không thể thêm vào Shadowing.");
        }

        JsonNode root = youtubeService.dumpJson(normalized);

        if (root.path("is_live").asBoolean(false)) {
            throw new IllegalArgumentException("Video đang phát trực tiếp — hãy chọn video đã ghi hình.");
        }

        YoutubeAnalyzeResultDto dto = new YoutubeAnalyzeResultDto();
        dto.setYoutubeVideoId(videoId);
        // Videos are played through YouTube's embed; one its owner does not allow to embed is downloaded instead.
        // Only an explicit false counts; a missing field is treated as embeddable.
        dto.setEmbeddable(!(root.path("playable_in_embed").isBoolean() && !root.path("playable_in_embed").asBoolean()));
        dto.setUrl(normalized);
        dto.setTitle(root.path("title").asText("YouTube Video"));
        dto.setDuration((int) Math.round(root.path("duration").asDouble(0)));
        dto.setChannel(root.path("channel").asText(root.path("uploader").asText("")));
        dto.setThumbnail(pickThumbnail(root));
        boolean hasManual = youtubeService.hasJapaneseTrack(root, "subtitles");
        boolean hasAuto = youtubeService.hasJapaneseTrack(root, "automatic_captions");
        dto.setHasSubtitle(hasManual);
        dto.setHasAutoSubtitle(hasAuto);

        dto.setViManual(youtubeService.hasLangTrack(root, "subtitles", "vi"));
        dto.setViAuto(youtubeService.hasLangTrack(root, "automatic_captions", "vi"));
        dto.setEnManual(youtubeService.hasLangTrack(root, "subtitles", "en"));
        dto.setEnAuto(youtubeService.hasLangTrack(root, "automatic_captions", "en"));

        String jaManualLang = youtubeService.findBestLangCode(root, "subtitles", "ja");
        String jaAutoLang = youtubeService.findBestLangCode(root, "automatic_captions", "ja");
        if (jaManualLang != null) {
            dto.setJapaneseSubtitleLang(jaManualLang);
            dto.setJapaneseSubtitleAuto(false);
        } else if (jaAutoLang != null) {
            dto.setJapaneseSubtitleLang(jaAutoLang);
            dto.setJapaneseSubtitleAuto(true);
        }

        applyLanguageDetection(root, dto, hasManual || hasAuto);

        String viManualLang = youtubeService.findBestLangCode(root, "subtitles", "vi");
        String viAutoLang = youtubeService.findBestLangCode(root, "automatic_captions", "vi");
        if (viManualLang != null) {
            dto.setViSubtitleLang(viManualLang);
            dto.setViSubtitleAuto(false);
        } else if (viAutoLang != null) {
            dto.setViSubtitleLang(viAutoLang);
            dto.setViSubtitleAuto(true);
        }

        String enManualLang = youtubeService.findBestLangCode(root, "subtitles", "en");
        String enAutoLang = youtubeService.findBestLangCode(root, "automatic_captions", "en");
        if (enManualLang != null) {
            dto.setEnSubtitleLang(enManualLang);
            dto.setEnSubtitleAuto(false);
        } else if (enAutoLang != null) {
            dto.setEnSubtitleLang(enAutoLang);
            dto.setEnSubtitleAuto(true);
        }

        boolean hasYtTranslation = dto.getViSubtitleLang() != null || dto.getEnSubtitleLang() != null;

        boolean needsStt = !hasManual && !hasAuto;
        dto.setRequiresAiStt(needsStt);
        dto.setBlockedNoSubtitleTooLong(needsStt && dto.getDuration() > maxDurationNoSubtitleSeconds);

        if (hasManual || hasAuto) {
            dto.setAiCostLabel(hasYtTranslation ? "0" : "Furigana AI");
        } else if (dto.isBlockedNoSubtitleTooLong()) {
            dto.setAiCostLabel("—");
            dto.setMessage("Video quá dài và không có phụ đề. Vui lòng chọn video dưới 5 phút.");
        } else {
            dto.setAiCostLabel("Deepgram STT");
            dto.setMessage("Không có phụ đề — sẽ dùng AI nhận dạng giọng nói (≤ 5 phút).");
        }

        if (dto.getDuration() <= 0) {
            throw new IllegalArgumentException("Không đọc được thời lượng video.");
        }

        return dto;
    }

    private void applyLanguageDetection(JsonNode root, YoutubeAnalyzeResultDto dto, boolean hasJapaneseTrack) {
        String declared = firstText(root, "language", "original_language", "audio_language", "language_code");
        String normalized = normalizeLanguageCode(declared);

        if (normalized != null) {
            dto.setLanguage(normalized);
            dto.setDetectedLanguage(normalized);
            dto.setDetectedLanguageLabel(languageLabel(normalized));
            dto.setLanguageConfidence(0.96);
            dto.setJapaneseDetected("ja".equals(normalized));
            dto.setBlockedNonJapanese(!dto.isJapaneseDetected());
            dto.setLanguageMessage(dto.isJapaneseDetected()
                    ? "Đã nhận diện tiếng Nhật từ metadata của video."
                    : "Video được nhận diện là " + languageLabel(normalized)
                            + ". JLAS chỉ nhận nội dung tiếng Nhật.");
            return;
        }

        if (hasJapaneseTrack) {
            dto.setLanguage("ja");
            dto.setDetectedLanguage("ja");
            dto.setDetectedLanguageLabel("Tiếng Nhật");
            dto.setLanguageConfidence(0.98);
            dto.setJapaneseDetected(true);
            dto.setLanguageMessage("Đã xác định tiếng Nhật dựa trên phụ đề JP của video.");
            return;
        }

        dto.setLanguage("unknown");
        dto.setDetectedLanguage("unknown");
        dto.setDetectedLanguageLabel("Chưa xác định");
        dto.setLanguageConfidence(0);
        dto.setJapaneseDetected(false);
        dto.setLanguageMessage("Chưa có metadata hoặc phụ đề JP để kết luận. Audio sẽ được kiểm tra lại trước khi tạo bài.");
    }

    private String firstText(JsonNode root, String... fields) {
        for (String field : fields) {
            String value = root.path(field).asText("").trim();
            if (!value.isBlank() && !"null".equalsIgnoreCase(value) && !"und".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return "";
    }

    private String normalizeLanguageCode(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("ja") || normalized.equals("jpn") || normalized.contains("japanese")
                || normalized.contains("日本")) return "ja";
        if (normalized.equals("und") || normalized.equals("unknown") || normalized.equals("none")) return null;
        if (normalized.length() >= 2) {
            String prefix = normalized.substring(0, 2);
            if (prefix.matches("[a-z]{2}")) return prefix;
        }
        return "other";
    }

    private String languageLabel(String code) {
        return switch (code) {
            case "ja" -> "Tiếng Nhật";
            case "en" -> "Tiếng Anh";
            case "zh" -> "Tiếng Trung";
            case "ko" -> "Tiếng Hàn";
            case "fr" -> "Tiếng Pháp";
            case "de" -> "Tiếng Đức";
            case "es" -> "Tiếng Tây Ban Nha";
            case "vi" -> "Tiếng Việt";
            default -> "ngôn ngữ khác";
        };
    }

    public Optional<TranslationTrack> resolveTranslationTrack(YoutubeAnalyzeResultDto meta, String targetLanguage) {
        if ("en".equalsIgnoreCase(targetLanguage)) {
            if (meta.getEnSubtitleLang() != null) {
                return Optional.of(new TranslationTrack(meta.getEnSubtitleLang(), meta.isEnSubtitleAuto()));
            }
            if (meta.getViSubtitleLang() != null) {
                return Optional.of(new TranslationTrack(meta.getViSubtitleLang(), meta.isViSubtitleAuto()));
            }
        } else {
            if (meta.getViSubtitleLang() != null) {
                return Optional.of(new TranslationTrack(meta.getViSubtitleLang(), meta.isViSubtitleAuto()));
            }
            if (meta.getEnSubtitleLang() != null) {
                return Optional.of(new TranslationTrack(meta.getEnSubtitleLang(), meta.isEnSubtitleAuto()));
            }
        }
        return Optional.empty();
    }

    public Optional<TranslationTrack> resolveJapaneseTrack(YoutubeAnalyzeResultDto meta) {
        if (meta.getJapaneseSubtitleLang() == null) return Optional.empty();
        return Optional.of(new TranslationTrack(meta.getJapaneseSubtitleLang(), meta.isJapaneseSubtitleAuto()));
    }

    private String pickThumbnail(JsonNode root) {
        String direct = root.path("thumbnail").asText("");
        if (!direct.isBlank()) return direct;
        JsonNode thumbs = root.path("thumbnails");
        if (thumbs.isArray() && !thumbs.isEmpty()) {
            return thumbs.get(thumbs.size() - 1).path("url").asText("");
        }
        return "";
    }
}
