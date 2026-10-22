package com.jlas.shadowing.repository;

import com.jlas.shadowing.model.Video;
import com.jlas.shadowing.model.VideoStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface VideoRepository extends JpaRepository<Video, Long> {
    List<Video> findByStatusOrderByCreatedAtDesc(VideoStatus status);
    List<Video> findBySourceUrlContaining(String fragment);
    List<Video> findByModerationStatus(String moderationStatus);
    List<Video> findByStatusInOrderByCreatedAtDesc(List<VideoStatus> statuses);

    List<Video> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    List<Video> findByOwnerIdAndStatusInOrderByCreatedAtDesc(Long ownerId, List<VideoStatus> statuses);

    @Query("""
            SELECT DISTINCT v FROM Video v LEFT JOIN v.tags t
            WHERE v.status = com.jlas.shadowing.model.VideoStatus.READY
              AND (v.visibility = com.jlas.shadowing.model.VideoVisibility.PUBLIC
                   OR v.visibility IS NULL)
              AND (LOWER(v.title) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(t.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR EXISTS (SELECT s FROM Subtitle s WHERE s.video = v
                              AND LOWER(s.text) LIKE LOWER(CONCAT('%', :q, '%'))))
            ORDER BY v.createdAt DESC
            """)
    List<Video> searchPublic(@Param("q") String query);

    @Query("""
            SELECT DISTINCT v FROM Video v
            WHERE v.status = com.jlas.shadowing.model.VideoStatus.READY
              AND (v.visibility = com.jlas.shadowing.model.VideoVisibility.PUBLIC
                   OR v.visibility IS NULL)
            ORDER BY v.createdAt DESC
            """)
    List<Video> findAllPublicReady();

    @Query("""
            SELECT DISTINCT v FROM Video v JOIN v.tags t
            WHERE v.status = com.jlas.shadowing.model.VideoStatus.READY
              AND (v.visibility = com.jlas.shadowing.model.VideoVisibility.PUBLIC
                   OR v.visibility IS NULL)
              AND LOWER(t.name) = LOWER(:tag)
            ORDER BY v.createdAt DESC
            """)
    List<Video> findPublicByTag(@Param("tag") String tag);

    @Query("SELECT DISTINCT v FROM Video v LEFT JOIN v.tags t " +
           "WHERE v.status IN :statuses AND " +
           "(LOWER(v.title) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(t.name) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "EXISTS (SELECT s FROM Subtitle s WHERE s.video = v AND LOWER(s.text) LIKE LOWER(CONCAT('%', :q, '%')))) " +
           "ORDER BY v.createdAt DESC")
    List<Video> search(@Param("q") String query, @Param("statuses") List<VideoStatus> statuses);

    @Query("SELECT DISTINCT v FROM Video v JOIN v.tags t " +
           "WHERE v.status IN :statuses AND LOWER(t.name) = LOWER(:tag) " +
           "ORDER BY v.createdAt DESC")
    List<Video> findByTag(@Param("tag") String tag, @Param("statuses") List<VideoStatus> statuses);
}
