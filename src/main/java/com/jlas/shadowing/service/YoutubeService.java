package com.jlas.shadowing.service;

import com.jlas.shadowing.util.youtube.YoutubeUrlValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.jlas.shadowing.util.youtube.TranslationTrack;

@Service
public class YoutubeService {

    @Value("${yt-dlp.bin.path:yt-dlp}")
    private String ytdlpBin;

    @Value("${upload.path.video}")
    private String videoDir;

    @Value("${upload.path.audio}")
    private String audioDir;

    @Value("${upload.path.thumbnail}")
    private String thumbnailDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public JsonNode dumpJson(String url) throws Exception {
        String normalized = YoutubeUrlValidator.normalizeWatchUrl(url);
        List<String> cmd = buildYoutubeCommand(
                "--dump-json",
                "--no-download",
                "--no-warnings",
                "--no-playlist",
                normalized
        );
        String output = runCommand(cmd, Duration.ofSeconds(90));
        String jsonLine = output.lines()
                .filter(l -> l.trim().startsWith("{"))
                .reduce((a, b) -> b)
                .orElse(output.trim());
        if (jsonLine.isBlank()) {
            throw new IllegalArgumentException("Không đọc được metadata YouTube. Video có thể bị xóa hoặc bị chặn.");
        }
        return objectMapper.readTree(jsonLine);
    }

    public boolean hasJapaneseTrack(JsonNode root, String field) {
        return hasLangTrack(root, field, "ja");
    }

    public boolean hasLangTrack(JsonNode root, String field, String... langPrefixes) {
        return findBestLangCode(root, field, langPrefixes) != null;
    }

    public String findBestLangCode(JsonNode root, String field, String... langPrefixes) {
        JsonNode node = root.path(field);
        if (!node.isObject()) return null;
        List<String> langs = new ArrayList<>();
        node.fieldNames().forEachRemaining(langs::add);
        for (String prefix : langPrefixes) {
            for (String lang : langs) {
                if (lang.equalsIgnoreCase(prefix)) return lang;
            }
            for (String lang : langs) {
                if (lang.toLowerCase().startsWith(prefix.toLowerCase() + "-")) return lang;
            }
        }
        return null;
    }

    public String downloadVideo(String url) throws Exception {
        return downloadVideo(url, Duration.ofMinutes(15));
    }

    public String downloadVideo(String url, Duration timeout) throws Exception {
        ensureDir(videoDir);
        String normalized = YoutubeUrlValidator.normalizeWatchUrl(url);
        String outPath = new File(videoDir, UUID.randomUUID() + ".mp4").getAbsolutePath();

        // Prefer mobile clients first — works without Node/Deno JS runtime on many machines
        String[] formatAttempts = {
                "best[height<=720][ext=mp4]/best[height<=720]/best",
                "bv*[height<=720]+ba/b[height<=720]/b",
                "best"
        };
        Exception last = null;
        for (String format : formatAttempts) {
            try {
                List<String> cmd = buildYoutubeCommand(
                        "-f", format,
                        "--merge-output-format", "mp4",
                        "--no-playlist",
                        "-o", outPath,
                        normalized
                );
                runCommand(cmd, timeout);
                if (new File(outPath).exists() && new File(outPath).length() > 0) {
                    return outPath;
                }
            } catch (Exception e) {
                last = e;
                System.err.println("[yt-dlp] video format attempt failed (" + format + "): " + e.getMessage());
                try {
                    Files.deleteIfExists(Path.of(outPath));
                } catch (Exception ignored) {
                }
            }
        }
        if (last != null) throw last;
        throw new IllegalStateException("Không tải được video từ YouTube.");
    }

    public String downloadAudio(String url) throws Exception {
        ensureDir(audioDir);
        String normalized = YoutubeUrlValidator.normalizeWatchUrl(url);
        String outPath = new File(audioDir, "yt_" + UUID.randomUUID() + ".mp3").getAbsolutePath();
        List<String> cmd = buildYoutubeCommand(
                "-f", "bestaudio/best",
                "-x",
                "--audio-format", "mp3",
                "--no-playlist",
                "-o", outPath,
                normalized
        );
        runCommand(cmd, Duration.ofMinutes(8));
        if (!new File(outPath).exists()) {
            throw new IllegalStateException("Không tải được audio từ YouTube.");
        }
        return outPath;
    }

    public Path downloadJapaneseSubtitle(String url, TranslationTrack track) throws Exception {
        Path sub = downloadSubtitleTracks(url, List.of(track)).get(track.langCode());
        if (sub == null) {
            throw new IllegalArgumentException("Không tải được phụ đề tiếng Nhật từ YouTube.");
        }
        return sub;
    }

    public Map<String, Path> downloadSubtitleTracks(String url, List<TranslationTrack> tracks) throws Exception {
        if (tracks == null || tracks.isEmpty()) return Map.of();

        Path tempDir = Files.createTempDirectory("yt_sub_");
        String normalized = YoutubeUrlValidator.normalizeWatchUrl(url);
        String outTemplate = tempDir.resolve("sub").toString();

        // Prefer batch download first; if primary JA missing, download each track alone
        Map<String, Path> batch = tryDownloadSubtitleBatch(normalized, outTemplate, tempDir, tracks);
        TranslationTrack primary = tracks.get(0);
        if (batch.containsKey(primary.langCode())) {
            return batch;
        }

        Map<String, Path> result = new HashMap<>(batch);
        for (TranslationTrack track : tracks) {
            if (result.containsKey(track.langCode())) continue;
            Path singleDir = Files.createTempDirectory(tempDir, "one_");
            String oneOut = singleDir.resolve("sub").toString();
            Map<String, Path> one = tryDownloadSubtitleBatch(
                    normalized, oneOut, singleDir, List.of(track));
            if (one.containsKey(track.langCode())) {
                result.put(track.langCode(), one.get(track.langCode()));
            }
        }
        return result;
    }

    private Map<String, Path> tryDownloadSubtitleBatch(
            String normalizedUrl,
            String outTemplate,
            Path tempDir,
            List<TranslationTrack> tracks
    ) throws Exception {
        Set<String> langCodes = tracks.stream()
                .map(TranslationTrack::langCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // ja.* matches ja, ja-JP, ja-orig from yt-dlp
        String langList = langCodes.stream()
                .map(code -> code.contains("*") || code.contains("-") ? code : code + ".*")
                .collect(Collectors.joining(","));

        boolean needManual = tracks.stream().anyMatch(t -> !t.autoOnly());
        boolean needAuto = tracks.stream().anyMatch(TranslationTrack::autoOnly);
        // If any track is auto, always request auto; also try manual when mixed
        if (!needManual && !needAuto) {
            needManual = true;
            needAuto = true;
        }

        RuntimeException lastError = null;
        TranslationTrack primary = tracks.get(0);
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                List<String> cmd = buildYoutubeCommand();
                // Always try both when unsure — yt-dlp ignores missing kinds
                if (needManual || attempt > 1) {
                    cmd.add("--write-subs");
                }
                if (needAuto || attempt > 1) {
                    cmd.add("--write-auto-subs");
                }
                cmd.addAll(List.of(
                        "--sub-langs", langList,
                        "--sub-format", "vtt/best",
                        "--convert-subs", "vtt",
                        "--skip-download",
                        "--no-playlist",
                        "-o", outTemplate,
                        normalizedUrl
                ));
                try {
                    runCommand(cmd, Duration.ofMinutes(4));
                } catch (RuntimeException e) {
                    lastError = e;
                    Map<String, Path> partial = collectSubtitleFiles(tempDir, tracks);
                    if (partial.containsKey(primary.langCode())) {
                        return partial;
                    }
                    if (e.getMessage() != null && e.getMessage().contains("429") && attempt < 3) {
                        Thread.sleep(4000L * attempt);
                        continue;
                    }
                    // keep trying next attempt with both manual+auto
                    if (attempt < 3) continue;
                    throw e;
                }
                Map<String, Path> found = collectSubtitleFiles(tempDir, tracks);
                if (!found.isEmpty()) {
                    return found;
                }
                // also scan any .vtt under tempDir and rematch
                found = collectSubtitleFilesLoose(tempDir, tracks);
                if (!found.isEmpty()) {
                    return found;
                }
            } catch (RuntimeException e) {
                lastError = e;
                if (e.getMessage() != null && e.getMessage().contains("429") && attempt < 3) {
                    Thread.sleep(4000L * attempt);
                    continue;
                }
                Map<String, Path> partial = collectSubtitleFilesLoose(tempDir, tracks);
                if (partial.containsKey(primary.langCode())) {
                    return partial;
                }
                if (attempt >= 3) break;
            }
        }
        Map<String, Path> partial = collectSubtitleFilesLoose(tempDir, tracks);
        if (!partial.isEmpty()) return partial;
        if (lastError != null) {
            System.err.println("[yt-dlp subs] batch failed: " + lastError.getMessage());
        }
        return Map.of();
    }

    public Path downloadSubtitle(String url, TranslationTrack track) throws Exception {
        return downloadSubtitleTracks(url, List.of(track)).get(track.langCode());
    }

    public String downloadThumbnailFromUrl(String thumbnailUrl) throws Exception {
        if (thumbnailUrl == null || thumbnailUrl.isBlank()) return null;
        ensureDir(thumbnailDir);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(thumbnailUrl))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) return null;

        String ext = thumbnailUrl.contains(".png") ? ".png" : ".jpg";
        File target = new File(thumbnailDir, UUID.randomUUID() + ext);
        Files.write(target.toPath(), response.body());
        return target.getAbsolutePath();
    }

    public String downloadThumbnailWithYtdlp(String url) throws Exception {
        ensureDir(thumbnailDir);
        String normalized = YoutubeUrlValidator.normalizeWatchUrl(url);
        String outTemplate = new File(thumbnailDir, UUID.randomUUID().toString()).getAbsolutePath();
        List<String> cmd = buildYoutubeCommand(
                "--write-thumbnail",
                "--skip-download",
                "--no-playlist",
                "-o", outTemplate,
                normalized
        );
        runCommand(cmd, Duration.ofMinutes(2));
        return findNewestFile(thumbnailDir, List.of(".jpg", ".jpeg", ".png", ".webp"));
    }

    private Map<String, Path> collectSubtitleFiles(Path dir, List<TranslationTrack> tracks) throws Exception {
        return collectSubtitleFilesLoose(dir, tracks);
    }

    /** Match ja / ja-JP / ja-orig / en-US etc. to requested base lang codes. */
    private Map<String, Path> collectSubtitleFilesLoose(Path dir, List<TranslationTrack> tracks) throws Exception {
        Map<String, Path> found = new HashMap<>();
        if (dir == null || !Files.isDirectory(dir)) return found;
        for (TranslationTrack track : tracks) {
            Path file = findSubtitleFileByLang(dir, track.langCode());
            if (file != null) {
                found.put(track.langCode(), file);
            }
        }
        return found;
    }

    private Path findSubtitleFileByLang(Path dir, String langCode) throws Exception {
        String want = langCode.toLowerCase();
        String base = want.contains("-") ? want.substring(0, want.indexOf('-')) : want;
        try (Stream<Path> stream = Files.walk(dir)) {
            List<Path> candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".vtt") || name.endsWith(".srt");
                    })
                    .filter(p -> langTagMatches(extractSubtitleLang(p.getFileName().toString()), want, base))
                    .sorted(Comparator
                            .comparing((Path p) -> scoreLangFile(p.getFileName().toString(), want, base)).reversed()
                            .thenComparing(p -> p.getFileName().toString()))
                    .toList();
            return candidates.isEmpty() ? null : candidates.get(0);
        }
    }

    private static boolean langTagMatches(String fileLang, String want, String base) {
        if (fileLang == null || fileLang.isBlank()) return false;
        String fl = fileLang.toLowerCase();
        if (fl.equals(want) || fl.equals(base)) return true;
        if (fl.startsWith(want + "-") || fl.startsWith(base + "-")) return true;
        // ja-orig, en-orig from yt-dlp
        if (fl.startsWith(base + ".")) return true;
        return false;
    }

    private static int scoreLangFile(String filename, String want, String base) {
        String lang = extractSubtitleLang(filename);
        int score = 0;
        if (lang.equalsIgnoreCase(want)) score += 100;
        else if (lang.equalsIgnoreCase(base)) score += 80;
        else if (lang.toLowerCase().startsWith(base + "-")) score += 60;
        if (filename.toLowerCase().contains("auto")) score -= 5;
        if (lang.toLowerCase().contains("orig")) score += 10;
        return score;
    }

    private static String extractSubtitleLang(String filename) {
        String lower = filename.toLowerCase();
        if (!lower.endsWith(".vtt") && !lower.endsWith(".srt")) return "";
        int ext = lower.lastIndexOf('.');
        int langDot = lower.lastIndexOf('.', ext - 1);
        if (langDot < 0) return "";
        return lower.substring(langDot + 1, ext);
    }

    private String findNewestFile(String dirPath, List<String> extensions) throws Exception {
        File dir = new File(dirPath);
        File[] files = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase();
            return extensions.stream().anyMatch(lower::endsWith);
        });
        if (files == null || files.length == 0) {
            throw new IllegalStateException("yt-dlp không tạo được file đầu ra trong " + dirPath);
        }
        File newest = files[0];
        for (File f : files) {
            if (f.lastModified() > newest.lastModified()) newest = f;
        }
        return newest.getAbsolutePath();
    }

    private void ensureDir(String path) {
        File dir = new File(path);
        if (!dir.exists()) dir.mkdirs();
    }

    private List<String> buildCommand(String... args) throws IOException {
        List<String> cmd = new ArrayList<>(resolveYtdlpLauncher());
        cmd.addAll(List.of(args));
        return cmd;
    }

    /**
     * yt-dlp base command with YouTube extractor args that avoid requiring
     * Node/Deno JS runtime (common failure: "No supported JavaScript runtime").
     */
    private List<String> buildYoutubeCommand(String... args) throws IOException {
        List<String> cmd = new ArrayList<>(resolveYtdlpLauncher());
        cmd.add("--extractor-args");
        // android/ios often work without JS; web as fallback for metadata
        cmd.add("youtube:player_client=android,ios,tv,web");
        cmd.addAll(List.of(args));
        return cmd;
    }

    /** Resolve yt-dlp executable; falls back to python -m yt_dlp on Windows. */
    private List<String> resolveYtdlpLauncher() throws IOException {
        if (ytdlpBin != null && !ytdlpBin.isBlank()) {
            File bin = new File(ytdlpBin);
            if (bin.isFile()) {
                return List.of(bin.getAbsolutePath());
            }
            if (!ytdlpBin.contains("/") && !ytdlpBin.contains("\\")) {
                return List.of(ytdlpBin);
            }
        }
        String python = resolvePython();
        if (python != null) {
            return List.of(python, "-m", "yt_dlp");
        }
        throw new IOException(
                "Không tìm thấy yt-dlp. Cài: pip install yt-dlp "
                        + "và đặt yt-dlp.bin.path trong application.properties.");
    }

    private String resolvePython() {
        String[] candidates = {
                "python",
                "python3",
                "C:\\Users\\Admin\\AppData\\Local\\Programs\\Python\\Python313\\python.exe"
        };
        for (String candidate : candidates) {
            try {
                Process p = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return candidate;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String runCommand(List<String> command, Duration timeout) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("Không chạy được yt-dlp: " + String.join(" ", command)
                    + " — " + e.getMessage(), e);
        }

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("yt-dlp quá thời gian chờ (" + timeout.getSeconds() + "s)");
        }
        int code = process.exitValue();
        if (code != 0) {
            String msg = output.toString();
            String lower = msg.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("private video") || lower.contains("video is private")) {
                throw new IllegalArgumentException("Video riêng tư — không thể truy cập.");
            }
            if (lower.contains("video unavailable") || lower.contains("video is unavailable")
                    || lower.contains("not available") || lower.contains("has been removed")
                    || lower.contains("not found")) {
                throw new IllegalArgumentException(
                        "Video YouTube này không khả dụng. Video có thể đã bị xóa, đặt riêng tư, bị giới hạn khu vực hoặc link không còn đúng. Hãy kiểm tra lại link hoặc chọn video khác.");
            }
            throw new RuntimeException("yt-dlp thất bại (mã " + code + "): " + truncate(msg, 500));
        }
        return output.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
