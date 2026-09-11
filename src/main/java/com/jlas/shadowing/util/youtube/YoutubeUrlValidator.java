package com.jlas.shadowing.util.youtube;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YoutubeUrlValidator {

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?v=|embed/|shorts/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})");

    private YoutubeUrlValidator() { }

    public static boolean looksLikeYoutubeUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String trimmed = url.trim();
        return trimmed.contains("youtube.com") || trimmed.contains("youtu.be");
    }

    public static Optional<String> extractVideoId(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        String trimmed = url.trim();
        Matcher matcher = VIDEO_ID_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    public static String normalizeWatchUrl(String url) {
        String id = extractVideoId(url)
                .orElseThrow(() -> new IllegalArgumentException("Link YouTube không hợp lệ. Dùng dạng https://www.youtube.com/watch?v=... hoặc https://youtu.be/..."));
        return "https://www.youtube.com/watch?v=" + id;
    }

    public static void requireValid(String url) {
        if (!looksLikeYoutubeUrl(url)) {
            throw new IllegalArgumentException("Vui lòng nhập link YouTube hợp lệ.");
        }
        extractVideoId(url).orElseThrow(() ->
                new IllegalArgumentException("Không nhận diện được ID video. Kiểm tra lại link YouTube."));
    }
}
