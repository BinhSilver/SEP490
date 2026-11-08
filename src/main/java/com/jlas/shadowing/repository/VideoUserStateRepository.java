package com.jlas.shadowing.repository;

import com.jlas.shadowing.model.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface VideoUserStateRepository extends JpaRepository<VideoUserState, VideoUserStateId> {
    // Column-specific upserts avoid losing a vote/favorite when a watch ping races it.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT INTO video_user_state(user_id,video_id,rating) VALUES (:user,:video,:rating) "
            + "ON DUPLICATE KEY UPDATE rating=:rating", nativeQuery = true)
    void storeRating(@Param("user") Long user, @Param("video") Long video, @Param("rating") int rating);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT INTO video_user_state(user_id,video_id,is_favorite) VALUES (:user,:video,:favorite) "
            + "ON DUPLICATE KEY UPDATE is_favorite=:favorite", nativeQuery = true)
    void storeFavorite(@Param("user") Long user, @Param("video") Long video, @Param("favorite") boolean favorite);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT INTO video_user_state(user_id,video_id,is_favorite) VALUES (:user,:video,1) "
            + "ON DUPLICATE KEY UPDATE is_favorite=NOT is_favorite", nativeQuery = true)
    void toggleFavorite(@Param("user") Long user, @Param("video") Long video);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT INTO video_user_state(user_id,video_id,max_position_sec,counted_as_view,last_watched_at) "
            + "VALUES (:user,:video,:position,:counted,CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE "
            + "max_position_sec=GREATEST(max_position_sec,:position), "
            + "counted_as_view=(counted_as_view OR :counted),last_watched_at=CURRENT_TIMESTAMP(6)", nativeQuery = true)
    void storeWatch(@Param("user") Long user, @Param("video") Long video,
                    @Param("position") double position, @Param("counted") boolean counted);
    Optional<VideoUserState> findByUserIdAndVideoId(Long userId, Long videoId);
    List<VideoUserState> findByUserIdAndFavoriteTrue(Long userId);
    List<VideoUserState> findByVideoId(Long videoId);
    @Query("select avg(s.rating) from VideoUserState s where s.videoId = :videoId and s.rating is not null")
    Double averageRating(@Param("videoId") Long videoId);
    long countByVideoIdAndRatingIsNotNull(Long videoId);
    @Modifying void deleteByVideoId(Long videoId);
}
