package com.jlas.shadowing.service;

import com.jlas.auth.security.CurrentUser;
import com.jlas.auth.service.UserDirectoryPort;
import com.jlas.notification.service.UserNotificationPort;
import com.jlas.shadowing.dto.*;
import com.jlas.shadowing.model.*;
import com.jlas.shadowing.repository.*;
import com.jlas.shadowing.util.NullSafe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class InteractionService {

    private static final double VIEW_THRESHOLD = 0.8;

    @Autowired private VideoFavoriteRepository favoriteRepository;
    @Autowired private VideoRatingRepository ratingRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private CommentRatingRepository commentRatingRepository;
    @Autowired private VideoWatchSessionRepository watchSessionRepository;
    @Autowired private VideoRepository videoRepository;
    @Autowired private UserDirectoryPort userDirectory;
    @Autowired private UserNotificationPort notifications;

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
        Video video = videoRepository.findById(NullSafe.longId(videoId)).orElseThrow();
        return favoriteRepository.findByClientIdAndVideoId(effectiveClientId, videoId)
                .map(fav -> {
                    favoriteRepository.delete(NullSafe.ref(fav));
                    return false;
                })
                .orElseGet(() -> {
                    VideoFavorite fav = new VideoFavorite();
                    fav.setClientId(effectiveClientId);
                    fav.setVideo(video);
                    favoriteRepository.save(NullSafe.ref(fav));
                    return true;
                });
    }

    public RatingSummaryDto getRatingSummary(String clientId, Long videoId) {
        clientId = CurrentUser.currentUserId().map(String::valueOf).orElse(null);
        Video video = videoRepository.findById(NullSafe.longId(videoId)).orElseThrow();
        RatingSummaryDto dto = new RatingSummaryDto();
        dto.setAvgRating(video.getAvgRating());
        dto.setRatingCount(video.getRatingCount() != null ? video.getRatingCount() : 0);
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
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }
        Video video = videoRepository.findById(NullSafe.longId(videoId)).orElseThrow();
        VideoRating vr = ratingRepository.findByClientIdAndVideoId(effectiveClientId, videoId)
                .orElseGet(() -> {
                    VideoRating r = new VideoRating();
                    r.setClientId(effectiveClientId);
                    r.setVideo(video);
                    return r;
                });
        vr.setRating(rating);
        ratingRepository.save(vr);
        updateVideoRatingAggregates(video);
        return getRatingSummary(effectiveClientId, videoId);
    }

    private void updateVideoRatingAggregates(Video video) {
        Double avg = ratingRepository.averageByVideoId(video.getId());
        long count = ratingRepository.countByVideoId(video.getId());
        video.setAvgRating(avg != null ? Math.round(avg * 10.0) / 10.0 : null);
        video.setRatingCount((int) count);
        videoRepository.save(video);
    }

    public List<CommentDto> getComments(Long videoId, String clientId) {
        String currentClientId = CurrentUser.currentUserId().map(String::valueOf).orElse(null);
        return commentRepository.findByVideoIdOrderByCreatedAtDesc(videoId).stream()
                .filter(c -> !Boolean.TRUE.equals(c.getHidden()))
                .map(c -> {
                    commentRatingRepository.find(c.getId()).ifPresent(c::setRating);
                    return toCommentDto(c, currentClientId);
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public CommentDto addComment(Long videoId, CommentRequest req) {
        Long userId = CurrentUser.requireUserId();
        if (req.getContent() == null || req.getContent().isBlank()) {
            throw new IllegalArgumentException("Nội dung bình luận không được để trống");
        }
        String text = req.getContent().trim();
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
        if (video.getOwnerId() != null && !video.getOwnerId().equals(userId)) {
            notifications.notifyUser(video.getOwnerId(), "SHADOWING_COMMENT", "Bình luận mới về bài Shadowing",
                    saved.getDisplayName() + " đã bình luận về “" + video.getTitle() + "”.",
                    "/shadowing/player/" + video.getId(), "SHADOWING_COMMENT_" + saved.getId());
        }
        return toCommentDto(saved, String.valueOf(userId));
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

    @Transactional
    public WatchResultDto recordWatch(Long videoId, WatchRequest req) {
        Long currentUserId = CurrentUser.requireUserId();
        if (req.getDuration() == null || req.getDuration() <= 0) {
            return new WatchResultDto(false, 0);
        }
        Video video = videoRepository.findById(NullSafe.longId(videoId)).orElseThrow();
        double current = req.getCurrentTime() != null ? req.getCurrentTime() : 0;
        double duration = req.getDuration();

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

        boolean newlyCounted = false;
        if (!Boolean.TRUE.equals(session.getCountedAsView())
                && session.getMaxPositionSec() / duration >= VIEW_THRESHOLD) {
            session.setCountedAsView(true);
            video.setViewCount((video.getViewCount() != null ? video.getViewCount() : 0) + 1);
            videoRepository.save(video);
            newlyCounted = true;

        }

        watchSessionRepository.save(session);
        int viewCount = (int) watchSessionRepository.countViewsByVideoId(videoId);
        if (newlyCounted) notifyViewMilestone(video, viewCount);
        WatchResultDto result = new WatchResultDto(newlyCounted, viewCount);
        return result;
    }

    private void notifyViewMilestone(Video video, int views) {
        if (video.getOwnerId() == null) return;
        for (int milestone : new int[] {10, 50, 100, 500, 1000}) {
            if (views == milestone) {
                notifications.notifyUser(video.getOwnerId(), "SHADOWING_VIEW_MILESTONE", "Bài Shadowing đạt mốc mới",
                        "“" + video.getTitle() + "” đã đạt " + milestone + " lượt xem.",
                        "/shadowing/player/" + video.getId(), "SHADOWING_VIEW_" + video.getId() + "_" + milestone);
                return;
            }
        }
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
