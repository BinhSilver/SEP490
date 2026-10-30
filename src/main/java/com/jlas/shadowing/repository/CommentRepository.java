package com.jlas.shadowing.repository;

import com.jlas.shadowing.model.Comment;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    @Query("select c from Comment c where c.targetType = 'VIDEO' and c.targetId = :videoId order by c.createdAt desc")
    List<Comment> findByVideoIdOrderByCreatedAtDesc(@Param("videoId") Long videoId);

    @Query("select count(c) from Comment c where c.targetType = 'VIDEO' and c.targetId = :videoId and c.hidden = false")
    long countByVideoId(@Param("videoId") Long videoId);

    @Modifying
    @Query("delete from Comment c where c.targetType = 'VIDEO' and c.targetId = :videoId")
    void deleteByVideoId(@Param("videoId") Long videoId);
}
