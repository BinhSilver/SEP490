package com.jlas.shadowing.service;

import com.jlas.auth.security.CurrentUser;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.service.UserActionPolicyPort;
import com.jlas.shadowing.model.Video;
import com.jlas.shadowing.model.VideoReport;
import com.jlas.shadowing.repository.VideoReportRepository;
import com.jlas.shadowing.repository.VideoRepository;
import com.jlas.shadowing.util.NullSafe;
import com.jlas.shadowing.util.ShadowingModerationState;
import com.jlas.shadowing.util.VideoOwnership;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Users report a Shadowing video (copyright or otherwise). Once {@link #HIDE_AFTER_REPORTS} different people
 * have an open report on it, the video is hidden until a moderator decides: valid -> removed and its source
 * blocked, invalid -> visible again (see {@link ShadowingTakedownService}).
 */
@Service
@RequiredArgsConstructor
public class ShadowingReportService {
    /** Reports one account may file per rolling 24 hours, so reporting cannot be used to flood moderators. */
    static final int MAX_REPORTS_PER_DAY = 20;
    /** Distinct reporters needed before a video is hidden; fewer cannot take a video down on their own. */
    static final int HIDE_AFTER_REPORTS = 3;
    private static final int MAX_DETAIL = 500;

    public enum Reason {
        COPYRIGHT("Vi phạm bản quyền"),
        INAPPROPRIATE("Nội dung không phù hợp"),
        OTHER("Lý do khác");

        private final String label;

        Reason(String label) { this.label = label; }

        public String label() { return label; }

        static Reason parse(String code) {
            try {
                return valueOf(code == null ? "" : code.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "Hãy chọn lý do báo cáo.");
            }
        }

        /** Label for a stored reason code; unknown codes are shown as-is. */
        public static String labelOf(String code) {
            try {
                return valueOf(code).label;
            } catch (IllegalArgumentException | NullPointerException e) {
                return code;
            }
        }
    }

    private final VideoRepository videos;
    private final VideoReportRepository reports;
    private final VideoService videoService;
    private final UserActionPolicyPort actionPolicy;

    @Transactional
    /** @return true when this report made the video hidden (so the reporter can no longer open it). */
    public boolean report(Long videoId, String reasonCode, String detail) {
        Long me = CurrentUser.requireUserId();
        actionPolicy.assertCanAct(me);
        Reason reason = Reason.parse(reasonCode);
        String note = detail == null ? "" : detail.trim();
        if (reason == Reason.OTHER && note.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Hãy mô tả ngắn lý do báo cáo.");
        }
        if (note.length() > MAX_DETAIL) note = note.substring(0, MAX_DETAIL);

        Video video = videos.findById(NullSafe.longId(videoId))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy video."));
        videoService.requireReadable(video);
        if (VideoOwnership.isOwner(video, me)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Bạn không thể báo cáo video của chính mình.");
        }
        if (reports.existsByVideoIdAndReporterIdAndStatus(video.getId(), me, VideoReport.OPEN)) {
            throw new ApiException(ErrorCode.CONFLICT, "Bạn đã báo cáo video này rồi, quản trị viên đang xem xét.");
        }
        if (reports.countByReporterIdAndCreatedAtAfter(me, LocalDateTime.now().minusDays(1)) >= MAX_REPORTS_PER_DAY) {
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS, "Bạn đã gửi quá nhiều báo cáo hôm nay. Hãy thử lại sau.");
        }

        VideoReport report = new VideoReport();
        report.setVideoId(video.getId());
        report.setReporterId(me);
        report.setReason(reason.name());
        report.setDetail(note.isEmpty() ? null : note);
        reports.save(report);

        // One open report per reporter per video, so the open count equals the number of distinct reporters.
        boolean enough = reports.countByVideoIdAndStatus(video.getId(), VideoReport.OPEN) >= HIDE_AFTER_REPORTS;
        if (enough && !ShadowingModerationState.isLocked(video.getModerationStatus())) {
            video.setModerationStatus(ShadowingModerationState.REPORTED);
            video.setModerationSummary("Đang chờ quản trị viên xem xét báo cáo");
            video.setModerationCheckedAt(LocalDateTime.now());
            videos.save(video);
        }
        return ShadowingModerationState.REPORTED.equalsIgnoreCase(video.getModerationStatus());
    }
}
