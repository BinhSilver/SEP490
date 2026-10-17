package com.jlas.shadowing.repository;

import com.jlas.shadowing.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.*;
import java.util.stream.Collectors;

@Repository @RequiredArgsConstructor
public class VideoFavoriteRepository {
    private final VideoUserStateRepository states;
    public Optional<VideoFavorite> findByClientIdAndVideoId(String client, Long video) {
        Long user = userId(client); if (user == null) return Optional.empty();
        return states.findByUserIdAndVideoId(user, video).filter(VideoUserState::isFavorite)
                .map(this::projection);
    }
    public boolean existsByClientIdAndVideoId(String client, Long video) { return findByClientIdAndVideoId(client, video).isPresent(); }
    public Set<Long> findVideoIdSetByClientId(String client) {
        Long user = userId(client); if (user == null) return Set.of();
        return states.findByUserIdAndFavoriteTrue(user).stream().map(VideoUserState::getVideoId).collect(Collectors.toSet());
    }
    public VideoFavorite save(VideoFavorite favorite) {
        Long user = userId(favorite.getClientId());
        states.storeFavorite(user, favorite.getVideo().getId(), true);
        return favorite;
    }
    public void delete(VideoFavorite favorite) {
        Long user = userId(favorite.getClientId());
        states.storeFavorite(user, favorite.getVideo().getId(), false);
    }
    public boolean toggle(String client, Long video) {
        Long user = userId(client);
        states.toggleFavorite(user, video);
        return states.findByUserIdAndVideoId(user, video).map(VideoUserState::isFavorite).orElse(false);
    }
    public void deleteByVideoId(Long video) { states.deleteByVideoId(video); }
    private VideoFavorite projection(VideoUserState state) {
        VideoFavorite value = new VideoFavorite(); value.setClientId(String.valueOf(state.getUserId()));
        Video video = new Video(); video.setId(state.getVideoId()); value.setVideo(video); return value;
    }
    private Long userId(String value) { try { return Long.valueOf(value); } catch (Exception ex) { return null; } }
}
