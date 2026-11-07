package com.jlas.community.repository;

import com.jlas.community.entity.CommunityContent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CommunityContentRepository extends JpaRepository<CommunityContent, Long> {

    Optional<CommunityContent> findByContentTypeAndContentId(String contentType, Long contentId);

    List<CommunityContent> findByStatus(String status);

    /** Tạo dòng trạng thái nếu chưa có; chạy lại không đổi gì (UNIQUE content_type + content_id). */
    @Modifying
    @Query(value = "INSERT INTO community_content (content_type, content_id) VALUES (:type, :contentId) "
            + "ON DUPLICATE KEY UPDATE id = id", nativeQuery = true)
    int ensureRow(@Param("type") String contentType, @Param("contentId") Long contentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CommunityContent c where c.contentType = :type and c.contentId = :contentId")
    Optional<CommunityContent> lockBySource(@Param("type") String contentType, @Param("contentId") Long contentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CommunityContent c where c.id = :id")
    Optional<CommunityContent> lockById(@Param("id") Long id);
}
