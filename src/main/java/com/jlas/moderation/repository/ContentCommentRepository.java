package com.jlas.moderation.repository;

import com.jlas.moderation.entity.ContentComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Repository cho {@link ContentComment}. */
@Repository
public interface ContentCommentRepository extends JpaRepository<ContentComment, Long> {
    List<ContentComment> findByTargetIdOrderByCreatedAtDesc(Long targetId);
}
