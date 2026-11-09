package com.jlas.shadowing.repository;

import com.jlas.shadowing.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository @RequiredArgsConstructor
public class VideoRatingRepository {
    private final VideoUserStateRepository states;
    public Optional<VideoRating> findByClientIdAndVideoId(String client, Long video) {
        Long user = userId(client); if (user == null) return Optional.empty();
        return states.findByUserIdAndVideoId(user, video).filter(s -> s.getRating() != null).map(this::projection);
    }
    /** video id -> this user's rating, for the videos they have rated. */
    public Map<Long, Integer> findRatings(String client, Collection<Long> videos) {
        Long user = userId(client); if (user == null || videos.isEmpty()) return Map.of();
        return states.findByUserIdAndVideoIdInAndRatingIsNotNull(user, videos).stream()
                .collect(Collectors.toMap(VideoUserState::getVideoId, VideoUserState::getRating));
    }
    public VideoRating save(VideoRating rating) {
        Long user = userId(rating.getClientId());
        states.storeRating(user, rating.getVideo().getId(), rating.getRating());
        return rating;
    }
    public Double averageByVideoId(Long video) { return states.averageRating(video); }
    public long countByVideoId(Long video) { return states.countByVideoIdAndRatingIsNotNull(video); }
    public void deleteByVideoId(Long video) { states.deleteByVideoId(video); }
    private VideoRating projection(VideoUserState state) {
        VideoRating value = new VideoRating(); value.setClientId(String.valueOf(state.getUserId()));
        value.setRating(state.getRating()); Video video = new Video(); video.setId(state.getVideoId());
        value.setVideo(video); return value;
    }
    private Long userId(String value) { try { return Long.valueOf(value); } catch (Exception ex) { return null; } }
}
