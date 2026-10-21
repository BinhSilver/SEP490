package com.jlas.shadowing.service;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.moderation.report.ContentState;
import com.jlas.moderation.report.ReportDecision;
import com.jlas.moderation.report.ReportedContentPort;
import com.jlas.moderation.report.ReportedTarget;
import com.jlas.shadowing.model.Video;
import com.jlas.shadowing.model.VideoReport;
import com.jlas.shadowing.repository.VideoReportRepository;
import com.jlas.shadowing.repository.VideoRepository;
import com.jlas.shadowing.util.ShadowingModerationState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Plugs reported Shadowing videos into the moderation report queue (target type VIDEO). */
@Component
@RequiredArgsConstructor
public class ShadowingReportedContentPort implements ReportedContentPort {
    private static final String TARGET_TYPE = "VIDEO";

    private final VideoRepository videos;
    private final VideoReportRepository reports;
    private final UserRepository users;
    private final ShadowingTakedownService takedown;

    @Override
    public boolean supports(String targetType) {
        return TARGET_TYPE.equals(targetType);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReportedTarget> hiddenTargets() {
        return videos.findByModerationStatus(ShadowingModerationState.REPORTED).stream()
                .map(this::toTarget).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReportedTarget> find(String targetType, Long targetId) {
        return videos.findById(targetId).map(this::toTarget);
    }

    @Override
    @Transactional
    public void resolve(String targetType, Long targetId, ReportDecision decision) {
        Video video = videos.findById(targetId).orElseThrow();
        if (decision.valid()) {
            takedown.takeDown(video, decision.reason(), decision.moderatorId(), decision.decidedAt());
        } else {
            takedown.restore(video, decision.reason(), decision.moderatorId(), decision.decidedAt());
        }
    }

    private ReportedTarget toTarget(Video video) {
        List<VideoReport> open = reports.findByVideoIdAndStatus(video.getId(), VideoReport.OPEN);
        List<String> reasons = open.stream().map(report -> {
            String label = ShadowingReportService.Reason.labelOf(report.getReason());
            return report.getDetail() == null || report.getDetail().isBlank() ? label : label + ": " + report.getDetail();
        }).toList();
        String ownerName = video.getOwnerId() == null ? null
                : users.findById(video.getOwnerId()).map(User::getFullName).orElse(null);
        String preview = video.getTitle() == null ? "" : video.getTitle();
        if (video.getSourceUrl() != null && !video.getSourceUrl().isBlank()) preview += "\n" + video.getSourceUrl();
        return new ReportedTarget(TARGET_TYPE, video.getId(), stateOf(video), video.getOwnerId(), ownerName,
                preview, "/shadowing/player/" + video.getId(), open.size(), reasons, video.getModerationCheckedAt());
    }

    private static String stateOf(Video video) {
        String status = video.getModerationStatus();
        if (ShadowingModerationState.REPORTED.equalsIgnoreCase(status)) return ContentState.TEMPORARILY_HIDDEN;
        if (ShadowingModerationState.isRemoved(status)) return ContentState.REMOVED;
        return ContentState.PUBLISHED;
    }
}
