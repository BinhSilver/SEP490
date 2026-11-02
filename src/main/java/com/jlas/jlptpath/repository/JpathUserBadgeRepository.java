package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathUserBadge;
import com.jlas.jlptpath.entity.JpathUserBadgeId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Badges earned by learners. */
@Repository
public interface JpathUserBadgeRepository extends JpaRepository<JpathUserBadge, JpathUserBadgeId> {

    /** Inserts the badge unless the learner has it; 1 = this call granted it, 0 = it already existed. */
    @Modifying
    @Query(value = "INSERT IGNORE INTO jpath_user_badge(user_id, badge_code) VALUES (:u, :c)", nativeQuery = true)
    int insertIgnore(@Param("u") Long u, @Param("c") String c);

    List<JpathUserBadge> findByUserId(Long userId);
}
