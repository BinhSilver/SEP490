package com.jlas.shadowing.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.dto.ModerationTargetSnapshot;
import com.jlas.moderation.service.ModerationTargetHandler;
import com.jlas.shadowing.model.Comment;
import com.jlas.shadowing.model.Video;
import com.jlas.shadowing.model.VideoVisibility;
import com.jlas.shadowing.repository.CommentRepository;
import com.jlas.shadowing.repository.VideoRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ShadowingModerationTargetHandler implements ModerationTargetHandler {
    private final VideoRepository videos;
    private final CommentRepository comments;

    @Override
    public boolean supports(String targetType) {
        return "VIDEO".equals(targetType) || "VIDEO_COMMENT".equals(targetType);
    }

    @Override
    @Transactional(readOnly = true)
    public ModerationTargetSnapshot snapshot(String targetType, Long targetId) {
        if ("VIDEO".equals(targetType)) {
            return videos.findById(targetId)
                    .map(video -> new ModerationTargetSnapshot(video.getModerationSummary(),
                            "/shadowing/player/" + video.getId()))
                    .orElseGet(ModerationTargetSnapshot::empty);
        }
        return comments.findById(targetId)
                .map(comment -> new ModerationTargetSnapshot(comment.getContent(),
                        "/shadowing/player/" + comment.getTargetId()))
                .orElseGet(ModerationTargetSnapshot::empty);
    }

    @Override
    @Transactional
    public void resolve(String targetType, Long targetId, boolean approve, String note) {
        if ("VIDEO".equals(targetType)) {
            Video video = videos.findById(targetId)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Video không còn tồn tại"));
            video.setModerationStatus(approve ? "SAFE" : "BLOCKED");
            video.setModerationSummary(approve ? "Moderator đã duyệt" : fallback(note, "Moderator từ chối"));
            video.setModerationCheckedAt(LocalDateTime.now());
            video.setVisibility(approve ? VideoVisibility.PUBLIC : VideoVisibility.PRIVATE);
            videos.save(video);
            return;
        }
        Comment comment = comments.findById(targetId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Bình luận không còn tồn tại"));
        comment.setHidden(!approve);
        comments.save(comment);
    }

    private static String fallback(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
