package com.jlas.moderation.repository;

import com.jlas.moderation.entity.ContentComment;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContentCommentRepository extends JpaRepository<ContentComment, Long> {
    List<ContentComment> findByTargetIdOrderByCreatedAtDesc(Long targetId);
    Page<ContentComment> findByTargetTypeAndTargetIdAndIsHiddenFalseOrderByCreatedAtDesc(String targetType, Long targetId, Pageable pageable);
}
