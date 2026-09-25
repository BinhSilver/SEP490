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
        VideoUserState state = states.findByUserIdAndVideoId(user, favorite.getVideo().getId())
                .orElseGet(() -> VideoUserState.builder().userId(user).videoId(favorite.getVideo().getId()).build());
        state.setFavorite(true); states.save(state); return projection(state);
    }
    public void delete(VideoFavorite favorite) {
        Long user = userId(favorite.getClientId());
        states.findByUserIdAndVideoId(user, favorite.getVideo().getId()).ifPresent(state -> {
            state.setFavorite(false); states.save(state);
        });
    }
    public void deleteByVideoId(Long video) { states.deleteByVideoId(video); }
    private VideoFavorite projection(VideoUserState state) {
        VideoFavorite value = new VideoFavorite(); value.setClientId(String.valueOf(state.getUserId()));
        Video video = new Video(); video.setId(state.getVideoId()); value.setVideo(video); return value;
    }
    private Long userId(String value) { try { return Long.valueOf(value); } catch (Exception ex) { return null; } }
}
