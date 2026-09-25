package com.jlas.shadowing.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class FfmpegService {

    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            ".mp3", ".wav", ".m4a", ".ogg", ".flac", ".aac", ".opus"
    );

    @Value("${upload.path.audio}")
    private String audioPath;

    @Value("${ffmpeg.bin.path:ffmpeg}")
    private String ffmpegBin;

    @Value("${ffmpeg.audio.bitrate:32k}")
    private String audioBitrate;

    /** True when the path looks like a pure audio file (not video containers). */
    public static boolean isAudioFile(String mediaPath) {
        if (mediaPath == null || mediaPath.isBlank()) return false;
        String lower = mediaPath.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return false;
        return AUDIO_EXTENSIONS.contains(lower.substring(dot));
    }

    /**
     * Prepare mono 16 kHz MP3 for STT.
     * Video  extract track; audio  normalize to a separate *_stt.mp3 (never overwrite the source).
     */
    public String prepareAudioForStt(String mediaPath) throws Exception {
        if (isAudioFile(mediaPath)) {
            return normalizeAudio(mediaPath);
        }
        return extractAudio(mediaPath);
    }

    public String extractAudio(String videoFilePath) throws Exception {
        File audioDirFile = new File(audioPath);
        if (!audioDirFile.exists()) audioDirFile.mkdirs();

        Path videoPath = Paths.get(videoFilePath);
        String fileName = videoPath.getFileName().toString();
        String audioFileName = fileName.substring(0, fileName.lastIndexOf(".")) + ".mp3";

        File outputAudioFile = new File(audioDirFile.getAbsolutePath(), audioFileName);
        runFfmpeg(ffmpegBin, "-i", videoFilePath, "-vn", "-ac", "1", "-ar", "16000", "-b:a", audioBitrate, "-y", outputAudioFile.getAbsolutePath());
        return outputAudioFile.getAbsolutePath();
    }

    /** Re-encode any audio file to mono 16 kHz MP3 for Deepgram (distinct output path). */
    public String normalizeAudio(String audioFilePath) throws Exception {
        File audioDirFile = new File(audioPath);
        if (!audioDirFile.exists()) audioDirFile.mkdirs();

        Path source = Paths.get(audioFilePath);
        String baseName = source.getFileName().toString();
        int dot = baseName.lastIndexOf('.');
        String stem = dot > 0 ? baseName.substring(0, dot) : baseName;
        File outputAudioFile = new File(audioDirFile.getAbsolutePath(), stem + "_stt.mp3");

        runFfmpeg(ffmpegBin, "-i", audioFilePath, "-vn", "-ac", "1", "-ar", "16000", "-b:a", audioBitrate, "-y", outputAudioFile.getAbsolutePath());
        return outputAudioFile.getAbsolutePath();
    }

    public List<AudioSegment> splitAudioInHalf(String audioFilePath) throws Exception {
        double duration = getDuration(audioFilePath);
        double halfDuration = duration / 2.0;
        Path source = Paths.get(audioFilePath);
        String baseName = source.getFileName().toString();
        String stem = baseName.substring(0, baseName.lastIndexOf('.'));

        List<AudioSegment> segments = new ArrayList<>();

        String firstPath = stem + "_half0.mp3";
        File firstFile = new File(source.getParent().toFile(), firstPath);
        extractSegment(audioFilePath, 0, halfDuration, firstFile.getAbsolutePath());
        segments.add(new AudioSegment(firstFile.getAbsolutePath(), 0));

        String secondPath = stem + "_half1.mp3";
        File secondFile = new File(source.getParent().toFile(), secondPath);
        extractSegment(audioFilePath, halfDuration, duration - halfDuration, secondFile.getAbsolutePath());
        segments.add(new AudioSegment(secondFile.getAbsolutePath(), halfDuration));

        return segments;
    }

    public List<String> splitAudioIntoChunks(String audioFilePath, int chunkSeconds) throws Exception {
        double duration = getDuration(audioFilePath);
        List<String> paths = new ArrayList<>();
        Path source = Paths.get(audioFilePath);
        String baseName = source.getFileName().toString();
        String stem = baseName.substring(0, baseName.lastIndexOf('.'));

        int index = 0;
        for (double start = 0; start < duration; start += chunkSeconds) {
            String chunkName = stem + "_dg" + index + ".mp3";
            File chunkFile = new File(source.getParent().toFile(), chunkName);
            double segmentDuration = Math.min(chunkSeconds, duration - start);
            extractSegment(audioFilePath, start, segmentDuration, chunkFile.getAbsolutePath());
            paths.add(chunkFile.getAbsolutePath());
            index++;
        }
        return paths;
    }

    private void extractSegment(String audioFilePath, double startSec, double durationSec, String outputPath) throws Exception {
        runFfmpeg(ffmpegBin,
                "-i", audioFilePath,
                "-ss", String.valueOf(startSec),
                "-t", String.valueOf(durationSec),
                "-vn", "-ac", "1", "-ar", "16000", "-b:a", audioBitrate,
                "-y", outputPath);
    }

    /** Mono 16 kHz PCM WAV for MFCC embedding comparison. */
    public void extractSegmentToWav(String audioFilePath, double startSec, double durationSec, String outputPath) throws Exception {
        runFfmpeg(ffmpegBin,
                "-i", audioFilePath,
                "-ss", String.valueOf(startSec),
                "-t", String.valueOf(durationSec),
                "-vn", "-ac", "1", "-ar", "16000", "-acodec", "pcm_s16le",
                "-y", outputPath);
    }

    public void convertFileToWav(String inputPath, String outputPath) throws Exception {
        runFfmpeg(ffmpegBin,
                "-i", inputPath,
                "-vn", "-ac", "1", "-ar", "16000", "-acodec", "pcm_s16le",
                "-y", outputPath);
    }

    public static class AudioSegment {
        private final String path;
        private final double startOffset;

        public AudioSegment(String path, double startOffset) {
            this.path = path;
            this.startOffset = startOffset;
        }

        public String getPath() { return path; }
        public double getStartOffset() { return startOffset; }
    }

    public void deleteFiles(List<String> paths) {
        if (paths == null) return;
        for (String path : paths) {
            if (path != null) {
                File f = new File(path);
                if (f.exists()) f.delete();
            }
        }
    }

    public Double getDuration(String videoFilePath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(ffmpegBin, "-i", videoFilePath);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        double duration = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("Duration:")) {
                    String dur = line.split("Duration:")[1].split(",")[0].trim();
                    Double parsed = parseDuration(dur);
                    if (parsed != null) {
                        duration = parsed;
                        break;
                    }
                }
            }
        }
        process.waitFor();
        return duration;
    }

    static Double parseDuration(String value) {
        if (value == null || value.isBlank() || "N/A".equalsIgnoreCase(value.trim())) return null;
        String[] parts = value.trim().split(":");
        if (parts.length != 3) return null;
        try {
            return Integer.parseInt(parts[0]) * 3600.0
                    + Integer.parseInt(parts[1]) * 60.0
                    + Double.parseDouble(parts[2]);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Extract a single JPEG frame at {@code timeSec} (seek before input for speed).
     * @param maxWidth scale width (keep aspect); &lt;=0 to skip scale
     * @return absolute path of written file
     */
    public String extractFrameAt(String mediaPath, double timeSec, String outputJpgPath, int maxWidth) throws Exception {
        if (mediaPath == null || mediaPath.isBlank()) {
            throw new IllegalArgumentException("mediaPath required");
        }
        if (isAudioFile(mediaPath)) {
            throw new IllegalArgumentException("Cannot extract frame from audio-only file");
        }
        File out = new File(outputJpgPath);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        double t = Math.max(0, timeSec);
        List<String> args = new ArrayList<>();
        args.add(ffmpegBin);
        args.add("-ss");
        args.add(String.format(Locale.US, "%.3f", t));
        args.add("-i");
        args.add(mediaPath);
        args.add("-frames:v");
        args.add("1");
        if (maxWidth > 0) {
            args.add("-vf");
            args.add("scale=" + maxWidth + ":-1");
        }
        args.add("-q:v");
        args.add("3");
        args.add("-y");
        args.add(out.getAbsolutePath());
        runFfmpeg(args.toArray(new String[0]));
        if (!out.exists() || out.length() == 0) {
            throw new RuntimeException("FFmpeg produced empty frame: " + out.getAbsolutePath());
        }
        return out.getAbsolutePath();
    }

    private void runFfmpeg(String... args) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(args);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            while (reader.readLine() != null) { /* consume */ }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("FFmpeg failed with exit code " + exitCode);
        }
    }
}
