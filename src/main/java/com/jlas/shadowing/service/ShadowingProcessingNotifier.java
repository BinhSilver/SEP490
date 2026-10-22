package com.jlas.shadowing.service;

import com.jlas.notification.service.UserNotificationPort;
import com.jlas.shadowing.model.Video;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tells the owner when a video's subtitles are ready or processing stopped, so they can leave the page while
 * it runs. Best effort: a failed notification never fails the processing itself.
 */
@Component
class ShadowingProcessingNotifier {
    private static final Logger log = LoggerFactory.getLogger(ShadowingProcessingNotifier.class);
    private static final int MAX_REASON = 300;
    private final UserNotificationPort notifications;

    ShadowingProcessingNotifier(UserNotificationPort notifications) {
        this.notifications = notifications;
    }

    void ready(Video video) {
        send(video, "SHADOWING_VIDEO_READY", "Phụ đề đã sẵn sàng",
                "「" + title(video) + "」 đã tạo xong phụ đề. Hãy kiểm tra lại rồi xuất bản.",
                "/shadowing/editor/" + video.getId(), "READY");
    }

    void failed(Video video, String reason) {
        String detail = reason == null || reason.isBlank() ? "Mở trang xử lý để thử lại hoặc tiếp tục."
                : reason.strip().length() > MAX_REASON ? reason.strip().substring(0, MAX_REASON) + "…" : reason.strip();
        send(video, "SHADOWING_VIDEO_FAILED", "Tạo phụ đề bị dừng", "「" + title(video) + "」: " + detail,
                "/shadowing/create/" + video.getId() + "/processing", "FAILED");
    }

    private void send(Video video, String type, String title, String message, String url, String outcome) {
        if (video == null || video.getOwnerId() == null) return;
        try {
            // Each run may finish again after a retry, so the key is per run, not per video.
            notifications.notifyUser(video.getOwnerId(), type, title, message, url,
                    "SHADOWING_" + outcome + "_" + video.getId() + "_" + System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.warn("Shadowing processing notification failed videoId={} ({})", video.getId(),
                    e.getClass().getSimpleName());
        }
    }

    private static String title(Video video) {
        String title = video.getTitle() == null || video.getTitle().isBlank() ? "Video #" + video.getId() : video.getTitle().strip();
        return title.length() > 120 ? title.substring(0, 120) + "…" : title;
    }
}
