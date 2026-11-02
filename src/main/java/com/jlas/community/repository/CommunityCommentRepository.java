package com.jlas.community.repository;

import com.jlas.community.entity.CommunityComment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CommunityCommentRepository extends JpaRepository<CommunityComment, Long> {

    /** Người thường: bình luận PUBLISHED, cộng bình luận đang ẩn của chính họ. */
    @Query("select c from CommunityComment c where c.communityContentId = :contentId and c.deletedAt is null "
            + "and (c.status = 'PUBLISHED' or (c.status = 'TEMPORARILY_HIDDEN' and c.userId = :viewerId)) "
            + "order by c.createdAt desc, c.id desc")
    Page<CommunityComment> findVisible(@Param("contentId") Long communityContentId,
                                       @Param("viewerId") Long viewerId, Pageable pageable);

    /** Moderator/Admin: mọi bình luận chưa xoá, kể cả đang ẩn/đã gỡ. */
    @Query("select c from CommunityComment c where c.communityContentId = :contentId and c.deletedAt is null "
            + "order by c.createdAt desc, c.id desc")
    Page<CommunityComment> findAllNotDeleted(@Param("contentId") Long communityContentId, Pageable pageable);

    long countByCommunityContentIdAndStatusAndDeletedAtIsNull(Long communityContentId, String status);

    List<CommunityComment> findByStatusAndDeletedAtIsNull(String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CommunityComment c where c.id = :id")
    Optional<CommunityComment> lockById(@Param("id") Long id);
}
