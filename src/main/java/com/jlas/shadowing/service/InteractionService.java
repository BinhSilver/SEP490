package com.jlas.shadowing.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.auth.security.CurrentUser;
import com.jlas.auth.service.UserDirectoryPort;
import com.jlas.game.repository.AvatarFrameJdbcRepository.Style;
import com.jlas.game.service.AvatarFrameService;
import com.jlas.moderation.dto.ModerationResult;
import com.jlas.moderation.service.ContentModerationPort;
import com.jlas.moderation.service.UserActionPolicyPort;
import com.jlas.notification.service.UserNotificationPort;
import com.jlas.shadowing.dto.*;
import com.jlas.shadowing.model.*;
import com.jlas.shadowing.repository.*;
import com.jlas.shadowing.util.NullSafe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class InteractionService {

    /** Seconds of real listening that make one view. */
    private static final double VIEW_LISTEN_SEC = 30;
    /** Share of a clip shorter than {@link #VIEW_LISTEN_SEC} that must be heard. */
    private static final double SHORT_CLIP_RATIO = 0.9;
    private static final Duration VIEW_COOLDOWN = Duration.ofHours(24);
    /** The client pings about every 5s; 15s leaves room for a missed ping at 2× speed. */
    private static final double MAX_LISTEN_PER_PING_SEC = 15;
    private static final double MAX_PLAYBACK_RATE = 2;

    @Autowired private VideoFavoriteRepository favoriteRepository;
    @Autowired private VideoRatingRepository ratingRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private CommentRatingRepository commentRatingRepository;
    @Autowired private VideoWatchSessionRepository watchSessionRepository;
    @Autowired private VideoRepository videoRepository;
    @Autowired private UserDirectoryPort userDirectory;
    @Autowired private ContentModerationPort moderationPort;
    @Autowired private UserActionPolicyPort actionPolicy;
    @Autowired private VideoViewRepository videoViews;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private UserNotificationPort notifications;
    /** Khung + màu tên người bình luận; không có (test dựng tay) thì để mặc định. */
    @Autowired(required = false) private AvatarFrameService frames;

    public Set<Long> getFavoriteVideoIds(String clientId) {
        return CurrentUser.currentUserId().map(String::valueOf)
                .map(favoriteRepository::findVideoIdSetByClientId).orElse(Set.of());
    }

    public boolean isFavorited(String clientId, Long videoId) {
        return CurrentUser.currentUserId().map(String::valueOf)
                .map(id -> favoriteRepository.existsByClientIdAndVideoId(id, videoId)).orElse(false);
    }

    @Transactional
    public boolean toggleFavorite(String clientId, Long videoId) {
        final String effectiveClientId = String.valueOf(CurrentUser.requireUserId());
        videoRepository.findById(NullSafe.longId(videoId)).orElseThrow();
        return favoriteRepository.toggle(effectiveClientId, videoId);
    }

    @Transactional(readOnly = true)
    public RatingSummaryDto getRatingSummary(String clientId, Long videoId) {
        clientId = CurrentUser.currentUserId().map(String::valueOf).orElse(null);
        videoRepository.findById(NullSafe.longId(videoId)).orElseThrow();
        RatingSummaryDto dto = new RatingSummaryDto();
        Double average = ratingRepository.averageByVideoId(videoId);
        dto.setAvgRating(average == null ? null : Math.round(average * 10.0) / 10.0);
        dto.setRatingCount(Math.toIntExact(ratingRepository.countByVideoId(videoId)));
        if (clientId != null) {
            ratingRepository.findByClientIdAndVideoId(clientId, videoId)
                    .ifPresent(r -> dto.setUserRating(r.getRating()));
        }
        return dto;
    }

    @Transactional
    public RatingSummaryDto saveRating(String clientId, Long videoId, int rating) {
        final String effectiveClientId = String.valueOf(CurrentUser.requireUserId());
        if (rating < 1 || rating > 5) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Đánh giá phải từ 1 đến 5 sao");
        }
        Video video = videoRepository.findById(NullSafe.longId(videoId)).orElseThrow();
        VideoRating vr = new VideoRating();
        vr.setClientId(effectiveClientId);
        vr.setVideo(video);
        vr.setRating(rating);
        ratingRepository.save(vr);
        return getRatingSummary(effectiveClientId, videoId);
    }

    public List<CommentDto> getComments(Long videoId, String clientId) {
        String currentClientId = CurrentUser.currentUserId().map(String::valueOf).orElse(null);
        return withProfiles(commentRepository.findByVideoIdOrderByCreatedAtDesc(videoId).stream()
                .filter(c -> !Boolean.TRUE.equals(c.getHidden()))
                .map(c -> {
                    commentRatingRepository.find(c.getId()).ifPresent(c::setRating);
                    return toCommentDto(c, currentClientId);
                })
                .collect(Collectors.toList()));
    }

    /**
     * Gắn tên hiển thị + ảnh đại diện + khung avatar + màu tên (mua ở Cửa hàng) cho mỗi người bình luận,
     * một lần truy vấn cho cả danh sách. Tên không lưu trong content_comment nên phải lấy lại từ hồ sơ.
     */
    private List<CommentDto> withProfiles(List<CommentDto> list) {
        if (list.isEmpty()) return list;
        List<Long> ids = list.stream().map(dto -> userIdOf(dto.getClientId())).filter(java.util.Objects::nonNull).toList();
        Map<Long, String> names = userDirectory == null ? Map.of() : userDirectory.displayNames(ids);
        Map<Long, String> avatars = userDirectory == null ? Map.of() : userDirectory.avatarUrls(ids);
        Map<Long, Style> styles = frames == null ? Map.of() : frames.styles(ids);
        list.forEach(dto -> {
            Long userId = userIdOf(dto.getClientId());
            if (userId == null) return;
            String name = names.get(userId);
            if (name != null) dto.setDisplayName(name);
            dto.setAvatarUrl(avatars.get(userId));
            Style style = styles.get(userId);
            if (style != null) {
                dto.setAvatarFrame(style.frameCode());
                dto.setNameStyle(style.nameStyle());
            }
        });
        return list;
    }

    /** clientId của bình luận là userId dạng chuỗi ("null" với dữ liệu cũ không có người dùng). */
    private static Long userIdOf(String clientId) {
        try {
            return clientId == null ? null : Long.valueOf(clientId);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Transactional
    public CommentDto addComment(Long videoId, CommentRequest req) {
        Long userId = CurrentUser.requireUserId();
        actionPolicy.assertCanAct(userId);
        if (req.getContent() == null || req.getContent().isBlank()) {
            throw new IllegalArgumentException("Nội dung bình luận không được để trống");
        }
        String text = req.getContent().trim();
        String rlKey = String.valueOf(userId);
        moderationPort.checkComment(rlKey);
        ModerationResult mod = moderationPort.scanText(text);
        if (mod.isBlocked()) {
            moderationPort.persistLog("VIDEO_COMMENT", videoId, userId, "COMMENT", text, mod);
            throw new ApiException(ErrorCode.CONTENT_BLOCKED,
                    mod.getSummary() != null ? mod.getSummary() : "Nội dung không phù hợp");
        }
        // JLAS-W0250: community comments publish immediately; a REVIEW verdict only queues a post-hoc check.
        boolean flagged = mod.needsReview();
        Video video = videoRepository.findById(NullSafe.longId(videoId)).orElseThrow();
        Comment comment = new Comment();
        comment.setTargetId(video.getId());
        comment.setUserId(userId);
        comment.setClientId(String.valueOf(userId));
        comment.setDisplayName(resolveCommentDisplayName(userId));
        comment.setContent(text);
        comment.setHidden(false);
        if (req.getRating() != null && req.getRating() >= 1 && req.getRating() <= 5) {
            comment.setRating(req.getRating());
            saveRating(String.valueOf(userId), videoId, req.getRating());
        }
        Comment saved = commentRepository.save(comment);
        if (req.getRating() != null && req.getRating() >= 1 && req.getRating() <= 5) {
            commentRatingRepository.save(saved.getId(), req.getRating());
        }
        var logEntity = moderationPort.persistLog("VIDEO_COMMENT", saved.getId(), userId, "COMMENT", text, mod);
        if (flagged) {
            moderationPort.enqueueReview(logEntity, "VIDEO_COMMENT", saved.getId(), userId,
                    "Video comment #" + videoId, mod.getSummary());
        }
        if (video.getOwnerId() != null && !video.getOwnerId().equals(userId)) {
            notifications.notifyUser(video.getOwnerId(), "SHADOWING_COMMENT", "Bình luận mới về bài Shadowing",
                    saved.getDisplayName() + " đã bình luận về “" + video.getTitle() + "”.",
                    "/shadowing/player/" + video.getId(), "SHADOWING_COMMENT_" + saved.getId());
        }
        return withProfiles(List.of(toCommentDto(saved, String.valueOf(userId)))).get(0);
    }

    @Transactional
    public void deleteComment(Long commentId, String clientId) {
        Long userId = CurrentUser.requireUserId();
        Comment comment = commentRepository.findById(NullSafe.longId(commentId)).orElseThrow();
        if (!userId.equals(comment.getUserId())) {
            throw new IllegalStateException("Không thể xóa bình luận của người khác");
        }
        commentRatingRepository.delete(commentId);
        commentRepository.delete(comment);
    }

    /**
     * Counts views like YouTube: 30s of real listening (seeks excluded) makes one view, each account
     * counts at most once per video every 24h, and the owner's own plays never count.
     */
    @Transactional
    public WatchResultDto recordWatch(Long videoId, WatchRequest req) {
        Long currentUserId = CurrentUser.requireUserId();
        Video video = videoRepository.findById(NullSafe.longId(videoId)).orElseThrow();
        if (req.getDuration() == null || !Double.isFinite(req.getDuration()) || req.getDuration() <= 0) {
            return new WatchResultDto(false, videoViews.viewCount(videoId), false);
        }
        double current = req.getCurrentTime() != null ? req.getCurrentTime() : 0;
        if (!Double.isFinite(current) || current < 0) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Vị trí phát không hợp lệ");
        }
        if (video.getDuration() != null && Double.isFinite(video.getDuration()) && video.getDuration() > 0)
            current = Math.min(current, video.getDuration());

        String clientId = String.valueOf(currentUserId);
        VideoWatchSession session = watchSessionRepository
                .findByClientIdAndVideoId(clientId, videoId)
                .orElseGet(() -> {
                    VideoWatchSession s = new VideoWatchSession();
                    s.setClientId(clientId);
                    s.setVideo(video);
                    return s;
                });
        session.setMaxPositionSec(Math.max(session.getMaxPositionSec(), current));

        if (currentUserId.equals(video.getOwnerId())) {
            watchSessionRepository.save(session);
            return new WatchResultDto(false, videoViews.viewCount(videoId), true);
        }

        LocalDateTime now = LocalDateTime.now();
        VideoViewRepository.Tracker tracker = videoViews.lockTracker(currentUserId, videoId);
        if (tracker.lastCountedAt() != null && tracker.lastCountedAt().plus(VIEW_COOLDOWN).isAfter(now)) {
            videoViews.saveTracker(currentUserId, videoId, 0, now, tracker.lastCountedAt());
            watchSessionRepository.save(session);
            return new WatchResultDto(false, videoViews.viewCount(videoId), true);
        }

        double listened = tracker.listenedSec() + creditedListenSec(req.getListenedSec(), tracker.lastPingAt(), now);
        // Use server metadata: a client cannot claim that a long video is a short clip.
        double duration = video.getDuration() != null && Double.isFinite(video.getDuration()) && video.getDuration() > 0
                ? video.getDuration() : VIEW_LISTEN_SEC / SHORT_CLIP_RATIO;
        // Clips shorter than 30s must be heard almost to the end instead.
        if (listened < Math.min(VIEW_LISTEN_SEC, duration * SHORT_CLIP_RATIO)) {
            videoViews.saveTracker(currentUserId, videoId, listened, now, tracker.lastCountedAt());
            watchSessionRepository.save(session);
            return new WatchResultDto(false, videoViews.viewCount(videoId), false);
        }

        videoViews.saveTracker(currentUserId, videoId, 0, now, now);
        session.setCountedAsView(true);
        watchSessionRepository.save(session);
        videoViews.recordViewEvent(currentUserId, videoId, now);
        int viewCount = videoViews.incrementViewCount(videoId);
        eventPublisher.publishEvent(new ShadowingViewCounted(videoId, video.getOwnerId(), video.getTitle(), viewCount));
        return new WatchResultDto(true, viewCount, true);
    }

    /**
     * Trusts the client's listening time only up to what the wall clock allows since the last ping
     * at the fastest playback speed, so replayed or parallel requests cannot inflate views.
     */
    private static double creditedListenSec(Double reported, LocalDateTime lastPingAt, LocalDateTime now) {
        if (reported == null || !Double.isFinite(reported) || !(reported > 0)) return 0;
        double cap = MAX_LISTEN_PER_PING_SEC;
        if (lastPingAt != null) {
            double elapsedSec = Duration.between(lastPingAt, now).toMillis() / 1000.0;
            cap = Math.min(cap, Math.max(0, elapsedSec) * MAX_PLAYBACK_RATE);
        }
        return Math.min(reported, cap);
    }

    private CommentDto toCommentDto(Comment c, String clientId) {
        CommentDto dto = new CommentDto();
        dto.setId(c.getId());
        dto.setVideoId(c.getTargetId());
        dto.setClientId(String.valueOf(c.getUserId()));
        dto.setDisplayName(c.getDisplayName() != null ? c.getDisplayName() : "Người dùng #" + c.getUserId());
        dto.setContent(c.getContent());
        dto.setRating(c.getRating());
        dto.setCreatedAt(c.getCreatedAt());
        dto.setOwn(clientId != null && clientId.equals(String.valueOf(c.getUserId())));
        return dto;
    }

    private String resolveCommentDisplayName(Long userId) {
        return userDirectory.displayName(userId);
    }
}
