package com.jlas.community.repository;

import com.jlas.community.entity.CommunitySavedPost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunitySavedPostRepository extends JpaRepository<CommunitySavedPost, Long> {

    boolean existsByUserIdAndContentTypeAndContentId(Long userId, String contentType, Long contentId);

    @Modifying
    @Query("delete from CommunitySavedPost s where s.userId = :userId and s.contentType = :type and s.contentId = :contentId")
    int deleteSaved(@Param("userId") Long userId, @Param("type") String contentType, @Param("contentId") Long contentId);
}
