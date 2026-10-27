package com.jlas.shadowing.repository;

import com.jlas.shadowing.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository @RequiredArgsConstructor
public class VideoWatchSessionRepository {
    private final VideoUserStateRepository states;
    public Optional<VideoWatchSession> findByClientIdAndVideoId(String client, Long video) {
        Long user = userId(client); if (user == null) return Optional.empty();
        return states.findByUserIdAndVideoId(user, video).map(this::projection);
    }
    public VideoWatchSession save(VideoWatchSession session) {
        Long user = userId(session.getClientId());
        states.storeWatch(user, session.getVideo().getId(), session.getMaxPositionSec(),
                Boolean.TRUE.equals(session.getCountedAsView()));
        return session;
    }
    public void deleteByVideoId(Long video) { states.deleteByVideoId(video); }
    private VideoWatchSession projection(VideoUserState state) {
        VideoWatchSession value = new VideoWatchSession(); value.setClientId(String.valueOf(state.getUserId()));
        value.setMaxPositionSec(state.getMaxPositionSec()); value.setCountedAsView(state.isCountedAsView());
        Video video = new Video(); video.setId(state.getVideoId()); value.setVideo(video); return value;
    }
    private Long userId(String value) { try { return Long.valueOf(value); } catch (Exception ex) { return null; } }
}
