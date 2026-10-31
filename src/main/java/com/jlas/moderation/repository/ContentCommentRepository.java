package com.jlas.moderation.repository;

import com.jlas.moderation.entity.ContentComment;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ContentCommentRepository extends JpaRepository<ContentComment, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ContentComment c where c.id = :id")
    Optional<ContentComment> findForUpdateById(@Param("id") Long id);

    List<ContentComment> findByTargetIdOrderByCreatedAtDesc(Long targetId);
    Page<ContentComment> findByTargetTypeAndTargetIdAndIsHiddenFalseOrderByCreatedAtDesc(String targetType, Long targetId, Pageable pageable);

    @Query(value = "select c.id from content_comment c left join content_comment_vote v on v.comment_id = c.id "
            + "where c.target_type = :type and c.target_id = :targetId and c.is_hidden = 0 "
            + "group by c.id order by coalesce(sum(case when v.vote = 1 then 1 else 0 end), 0) desc, c.created_at desc, c.id desc",
            countQuery = "select count(*) from content_comment c where c.target_type = :type "
                    + "and c.target_id = :targetId and c.is_hidden = 0", nativeQuery = true)
    Page<Long> findVisibleIdsByLikes(@Param("type") String type, @Param("targetId") Long targetId, Pageable pageable);

    @Query("select c from ContentComment c join fetch c.user where c.id in :ids")
    List<ContentComment> findWithAuthorByIdIn(@Param("ids") List<Long> ids);
}
