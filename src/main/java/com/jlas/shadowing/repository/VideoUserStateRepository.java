package com.jlas.shadowing.repository;

import com.jlas.shadowing.model.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface VideoUserStateRepository extends JpaRepository<VideoUserState, VideoUserStateId> {
    Optional<VideoUserState> findByUserIdAndVideoId(Long userId, Long videoId);
    List<VideoUserState> findByUserIdAndFavoriteTrue(Long userId);
    List<VideoUserState> findByVideoId(Long videoId);
    @Query("select avg(s.rating) from VideoUserState s where s.videoId = :videoId and s.rating is not null")
    Double averageRating(@Param("videoId") Long videoId);
    long countByVideoIdAndRatingIsNotNull(Long videoId);
    long countByVideoIdAndCountedAsViewTrue(Long videoId);
    @Modifying void deleteByVideoId(Long videoId);
}
