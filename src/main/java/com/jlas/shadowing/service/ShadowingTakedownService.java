package com.jlas.shadowing.service;

import com.jlas.moderation.service.ModerationNotifier;
import com.jlas.shadowing.model.BlockedVideoSource;
import com.jlas.shadowing.model.Video;
import com.jlas.shadowing.model.VideoReport;
import com.jlas.shadowing.model.VideoVisibility;
import com.jlas.shadowing.repository.BlockedVideoSourceRepository;
import com.jlas.shadowing.repository.VideoReportRepository;
import com.jlas.shadowing.repository.VideoRepository;
import com.jlas.shadowing.util.ShadowingModerationState;
import com.jlas.shadowing.util.youtube.YoutubeUrlValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Permanent removal of reported videos. A valid report removes the video AND blocks its YouTube source,
 * so neither the owner nor anyone else can import the same link again; other users' existing copies of
 * that link are removed with it.
 */
@Service
@RequiredArgsConstructor
public class ShadowingTakedownService {
    private static final int MAX_TEXT = 500;

    private final VideoRepository videos;
    private final VideoReportRepository reports;
    private final BlockedVideoSourceRepository blockedSources;
    private final ModerationNotifier notifier;

    /** {@code youtube:<id>} for any YouTube URL form, or empty for uploads and other sources. */
    public static Optional<String> sourceKey(String url) {
        return YoutubeUrlValidator.extractVideoId(url).map(id -> "youtube:" + id);
    }

    public boolean isBlockedYoutube(String youtubeVideoId) {
        return youtubeVideoId != null && blockedSources.existsById("youtube:" + youtubeVideoId);
    }

    public boolean isBlocked(String url) {
        return sourceKey(url).filter(blockedSources::existsById).isPresent();
    }

    /** Removes {@code origin} and every other copy of its YouTube source, blocks the source, closes their reports. */
    @Transactional
    public void takeDown(Video origin, String reason, Long moderatorId, LocalDateTime at) {
        String why = clip(reason);
        List<Video> affected = new ArrayList<>();
        affected.add(origin);

        Optional<String> key = sourceKey(origin.getSourceUrl());
        if (key.isPresent()) {
            if (!blockedSources.existsById(key.get())) {
                BlockedVideoSource blocked = new BlockedVideoSource();
                blocked.setSourceKey(key.get());
                blocked.setSourceUrl(origin.getSourceUrl());
                blocked.setReason(why);
                blocked.setVideoId(origin.getId());
                blocked.setBlockedBy(moderatorId);
                blocked.setBlockedAt(at);
                blockedSources.save(blocked);
            }
            String youtubeId = key.get().substring("youtube:".length());
            for (Video copy : videos.findBySourceUrlContaining(youtubeId)) {
                if (!copy.getId().equals(origin.getId()) && key.equals(sourceKey(copy.getSourceUrl()))) {
                    affected.add(copy);
                }
            }
        }

        for (Video video : affected) {
            remove(video, why, at);
            if (!video.getId().equals(origin.getId())) {
                notifier.contentRemoved(video.getOwnerId(), "VIDEO", video.getId(), why);
            }
        }
        closeReports(affected.stream().map(Video::getId).toList(), VideoReport.VALID, moderatorId, why, at);
    }

    /** Report was wrong: the video is visible again and its reports are closed as invalid. */
    @Transactional
    public void restore(Video video, String reason, Long moderatorId, LocalDateTime at) {
        String why = clip(reason);
        video.setModerationStatus("SAFE");
        video.setModerationSummary("Báo cáo không đúng: " + why);
        video.setModerationCheckedAt(at);
        videos.save(video);
        closeReports(List.of(video.getId()), VideoReport.INVALID, moderatorId, why, at);
    }

    private void remove(Video video, String why, LocalDateTime at) {
        video.setModerationStatus(ShadowingModerationState.REMOVED);
        video.setModerationSummary(why);
        video.setModerationCheckedAt(at);
        video.setVisibility(VideoVisibility.PRIVATE);
        videos.save(video);
    }

    private void closeReports(List<Long> videoIds, String status, Long moderatorId, String why, LocalDateTime at) {
        List<VideoReport> open = reports.findByVideoIdInAndStatus(videoIds, VideoReport.OPEN);
        for (VideoReport report : open) {
            report.setStatus(status);
            report.setDecidedBy(moderatorId);
            report.setDecidedAt(at);
            report.setDecisionReason(why);
        }
        reports.saveAll(open);
    }

    static String clip(String value) {
        String text = value == null ? "" : value.trim();
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    }
}
