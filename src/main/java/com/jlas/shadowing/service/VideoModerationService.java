package com.jlas.shadowing.service;

import com.jlas.moderation.dto.ModerationResult;
import com.jlas.moderation.model.ModerationVerdict;
import com.jlas.moderation.service.ContentModerationPort;
import com.jlas.shadowing.model.Subtitle;
import com.jlas.shadowing.model.Video;
import com.jlas.shadowing.model.VideoVisibility;
import com.jlas.shadowing.repository.SubtitleRepository;
import com.jlas.shadowing.repository.VideoRepository;
import com.jlas.shadowing.util.NullSafe;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Shadowing-owned video workflow. Moderation remains behind
 * {@link ContentModerationPort}; this service alone knows Shadowing entities.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoModerationService {

    private final VideoRepository videoRepository;
    private final SubtitleRepository subtitleRepository;
    private final ContentModerationPort moderationPort;
    private final FfmpegService ffmpegService;

    @Value("${moderation.enabled:true}")
    private boolean enabled;

    @Value("${moderation.video.enabled:true}")
    private boolean videoEnabled;

    @Value("${moderation.video.quick-scan:true}")
    private boolean quickScan;

    @Value("${moderation.video.targeted-scan:true}")
    private boolean targetedScan;

    @Value("${moderation.video.frame-max-width:768}")
    private int frameMaxWidth;

    @Value("${moderation.video.temp-dir:uploads/moderation-frames/}")
    private String tempDir;

    @Async
    public void moderateVideoAsync(Long videoId) {
        try {
            moderateVideo(videoId);
        } catch (Exception e) {
            log.error("Async video moderation failed id={}: {}", videoId, e.getMessage(), e);
            try {
                videoRepository.findById(NullSafe.longId(videoId)).ifPresent(video -> {
                    if (com.jlas.shadowing.util.ShadowingModerationState.isLocked(video.getModerationStatus())) return;
                    video.setModerationStatus(ModerationVerdict.ERROR.name());
                    video.setModerationSummary("Moderation error: " + e.getMessage());
                    video.setModerationCheckedAt(LocalDateTime.now());
                    videoRepository.save(video);
                });
            } catch (Exception ignored) {
                // Best effort: the original moderation failure is already logged.
            }
        }
    }

    @Transactional
    public ModerationResult moderateVideo(Long videoId) {
        Video video = videoRepository.findById(NullSafe.longId(videoId)).orElse(null);
        if (video == null) {
            return ModerationResult.builder()
                    .verdict(ModerationVerdict.ERROR)
                    .summary("Video not found")
                    .build();
        }
        if (!enabled || !videoEnabled) {
            ModerationResult result = ModerationResult.builder()
                    .verdict(ModerationVerdict.SAFE)
                    .summary("Video moderation disabled")
                    .modelName("off")
                    .confidence(1)
                    .categories(List.of())
                    .build();
            applyToVideo(video, result, null);
            return result;
        }

        List<ModerationResult> parts = new ArrayList<>();
        List<Subtitle> subtitles = subtitleRepository.findByVideoIdOrderBySortOrderAscStartTimeAsc(videoId);

        List<String> textParts = new ArrayList<>();
        List<String> blockedFindings = new ArrayList<>();
        if (StringUtils.hasText(video.getTitle())) {
            textParts.add(video.getTitle());
            ModerationResult titleResult = moderationPort.scanText(video.getTitle());
            if (titleResult.isBlocked() || titleResult.needsReview()) {
                parts.add(titleResult);
            }
            if (titleResult.isBlocked()) {
                blockedFindings.add("• Tiêu đề: “" + preview(video.getTitle()) + "”");
            }
        }

        List<Double> reviewTimestamps = new ArrayList<>();
        boolean anyText = false;
        for (int index = 0; index < subtitles.size(); index++) {
            Subtitle subtitle = subtitles.get(index);
            StringBuilder cue = new StringBuilder();
            if (StringUtils.hasText(subtitle.getText())) {
                cue.append(subtitle.getText());
                anyText = true;
            }
            if (StringUtils.hasText(subtitle.getTranslation())) {
                if (!cue.isEmpty()) cue.append(' ');
                cue.append(subtitle.getTranslation());
                anyText = true;
            }
            if (cue.isEmpty()) continue;
            textParts.add(cue.toString());
            ModerationResult cueResult = moderationPort.scanText(cue.toString());
            if (cueResult.isBlocked()) {
                parts.add(cueResult);
                blockedFindings.add("• Câu " + (index + 1) + " (" + formatTime(subtitle.getStartTime())
                        + "): “" + preview(cue.toString()) + "”");
            } else if (cueResult.needsReview()) {
                parts.add(cueResult);
                reviewTimestamps.add(midTime(subtitle));
            }
        }
        if (!textParts.isEmpty()) {
            parts.add(moderationPort.scanCombined(textParts));
        } else if (!anyText) {
            parts.add(ModerationResult.builder()
                    .verdict(ModerationVerdict.REVIEW)
                    .categories(List.of("NO_TRANSCRIPT"))
                    .confidence(0.5)
                    .summary("Không có phụ đề/transcript — cần vision + admin nếu public.")
                    .modelName("text-policy")
                    .build());
        }

        ModerationResult textMerge = moderationPort.mergeVerdicts(parts);
        if (textMerge.isBlocked()) {
            textMerge.setSummary(buildBlockedSummary(blockedFindings));
            Long logId = moderationPort.persistLog(
                    "VIDEO", videoId, video.getOwnerId(), "VIDEO_TEXT",
                    String.join("\n", textParts), textMerge);
            applyToVideo(video, textMerge, logId);
            if (video.getVisibility() == VideoVisibility.PUBLIC) {
                video.setVisibility(VideoVisibility.PRIVATE);
                videoRepository.save(video);
            }
            return textMerge;
        }

        List<ModerationResult> visionParts = new ArrayList<>();
        // Cloud files are the reviewed seed videos; like YouTube embeds they have no local frames to scan.
        String mediaPath = ShadowingBlobStore.isBlob(video.getVideoPath()) ? null : video.getVideoPath();
        boolean audioOnly = mediaPath != null && FfmpegService.isAudioFile(mediaPath);

        if (!audioOnly && mediaPath != null) {
            Set<Integer> usedSeconds = new LinkedHashSet<>();
            List<Double> times = new ArrayList<>();
            if (quickScan) {
                double duration = resolveDuration(video, mediaPath);
                if (duration > 0.5) {
                    times.add(duration * 0.10);
                    times.add(duration * 0.50);
                    times.add(duration * 0.90);
                } else if (duration > 0) {
                    times.add(0.0);
                }
            }
            if (targetedScan) {
                for (Double time : reviewTimestamps) {
                    if (time == null) continue;
                    times.add(Math.max(0, time - 1.0));
                    times.add(time);
                    times.add(time + 1.0);
                }
            }

            scanThumbnailIfPossible(video, visionParts);
            if (moderationPort.isVisionConfigured()) {
                for (Double time : times) {
                    int secondKey = (int) Math.floor(Math.max(0, time));
                    if (!usedSeconds.add(secondKey)) continue;
                    if (usedSeconds.size() > 20) break;
                    try {
                        Path frame = extractFrame(videoId, mediaPath, time);
                        visionParts.add(moderationPort.scanImage(frame, frame.getFileName().toString()));
                        try {
                            java.nio.file.Files.deleteIfExists(frame);
                        } catch (Exception ignored) {
                            // Temporary-file cleanup is best effort.
                        }
                    } catch (Exception e) {
                        log.warn("Frame @{}s video {}: {}", time, videoId, e.getMessage());
                        visionParts.add(ModerationResult.builder()
                                .verdict(ModerationVerdict.ERROR)
                                .summary("Frame extract failed @ " + time + "s")
                                .categories(List.of("FRAME_ERROR"))
                                .confidence(0)
                                .modelName("ffmpeg")
                                .build());
                    }
                }
            } else {
                visionParts.add(ModerationResult.builder()
                        .verdict(ModerationVerdict.SAFE)
                        .summary("Vision model chưa set — chỉ dựa text")
                        .modelName("vision-skipped")
                        .confidence(0.5)
                        .categories(List.of())
                        .build());
            }
        } else if (audioOnly) {
            visionParts.add(ModerationResult.builder()
                    .verdict(ModerationVerdict.SAFE)
                    .summary("Audio-only — bỏ frame vision")
                    .modelName("audio-skip")
                    .confidence(1)
                    .categories(List.of())
                    .build());
        }

        List<ModerationResult> all = new ArrayList<>(parts);
        all.addAll(visionParts);
        ModerationResult finalResult = moderationPort.mergeVerdicts(all);
        if (finalResult.getVerdict() == ModerationVerdict.ERROR) {
            finalResult = ModerationResult.builder()
                    .verdict(ModerationVerdict.REVIEW)
                    .categories(finalResult.getCategories())
                    .confidence(finalResult.getConfidence())
                    .summary("Lỗi kiểm duyệt / vision — chuyển REVIEW: " + finalResult.getSummary())
                    .modelName(finalResult.getModelName())
                    .build();
        }

        Long logId = moderationPort.persistLog(
                "VIDEO", videoId, video.getOwnerId(), "VIDEO_FULL",
                "title+subs+frames", finalResult);
        applyToVideo(video, finalResult, logId);
        return finalResult;
    }

    private void applyToVideo(Video video, ModerationResult result, Long moderationLogId) {
        // Reported / removed videos are decided by moderators; an AI rescan must not overwrite that.
        if (com.jlas.shadowing.util.ShadowingModerationState.isLocked(video.getModerationStatus())) return;
        video.setModerationStatus(result.getVerdict() != null ? result.getVerdict().name() : "ERROR");
        video.setModerationSummary(result.getSummary());
        video.setModerationCheckedAt(LocalDateTime.now());
        if (result.isBlocked() || result.needsReview()) {
            video.setVisibility(VideoVisibility.PRIVATE);
            if (result.needsReview() || result.getVerdict() == ModerationVerdict.REVIEW) {
                moderationPort.enqueueReview(moderationLogId, "VIDEO", video.getId(),
                        video.getOwnerId(), video.getTitle(), result.getSummary());
            }
        }
        videoRepository.save(video);
    }

    private void scanThumbnailIfPossible(Video video, List<ModerationResult> visionParts) {
        String thumbnail = video.getThumbnailPath();
        if (!StringUtils.hasText(thumbnail) || !moderationPort.isVisionConfigured()) return;
        try {
            Path path = resolveMaybeRelative(thumbnail);
            if (path != null && java.nio.file.Files.isRegularFile(path)) {
                visionParts.add(moderationPort.scanImage(path, path.getFileName().toString()));
            }
        } catch (Exception e) {
            log.debug("Thumbnail scan skip: {}", e.getMessage());
        }
    }

    private Path resolveMaybeRelative(String path) {
        Path candidate = Paths.get(path);
        if (java.nio.file.Files.isRegularFile(candidate)) return candidate;
        String relative = path.startsWith("/") ? path.substring(1) : path;
        candidate = Paths.get(relative);
        return java.nio.file.Files.isRegularFile(candidate) ? candidate : null;
    }

    private Path extractFrame(Long videoId, String mediaPath, double timeSec) throws Exception {
        Path directory = Paths.get(tempDir, String.valueOf(videoId)).toAbsolutePath().normalize();
        java.nio.file.Files.createDirectories(directory);
        String name = String.format(Locale.US, "f_%.2f.jpg", timeSec);
        String output = directory.resolve(name).toString();
        ffmpegService.extractFrameAt(mediaPath, timeSec, output, frameMaxWidth);
        return Paths.get(output);
    }

    private double resolveDuration(Video video, String mediaPath) {
        if (video.getDuration() != null && video.getDuration() > 0) return video.getDuration();
        try {
            Double duration = ffmpegService.getDuration(mediaPath);
            return duration != null ? duration : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static double midTime(Subtitle subtitle) {
        double start = subtitle.getStartTime() != null ? subtitle.getStartTime() : 0;
        double end = subtitle.getEndTime() != null ? subtitle.getEndTime() : start;
        if (end < start) end = start;
        return (start + end) / 2.0;
    }

    private static String buildBlockedSummary(List<String> findings) {
        if (findings == null || findings.isEmpty()) {
            return "Phát hiện nội dung không phù hợp trong tiêu đề hoặc phụ đề. "
                    + "Hãy kiểm tra lại nội dung rồi thử công khai lần nữa.";
        }
        int shown = Math.min(findings.size(), 5);
        StringBuilder summary = new StringBuilder("Phát hiện nội dung cần sửa trước khi công khai:\n");
        for (int index = 0; index < shown; index++) {
            if (index > 0) summary.append('\n');
            summary.append(findings.get(index));
        }
        if (findings.size() > shown) {
            summary.append("\n… và ").append(findings.size() - shown).append(" vị trí khác.");
        }
        return summary.length() > 480 ? summary.substring(0, 477) + "…" : summary.toString();
    }

    private static String preview(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return compact.length() > 72 ? compact.substring(0, 69) + "…" : compact;
    }

    private static String formatTime(Double seconds) {
        long total = (long) Math.floor(Math.max(0, seconds != null ? seconds : 0));
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long remainingSeconds = total % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainingSeconds)
                : String.format(Locale.ROOT, "%02d:%02d", minutes, remainingSeconds);
    }
}
