package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathStudySession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Study sessions. */
@Repository
public interface JpathStudySessionRepository extends JpaRepository<JpathStudySession, Long> {

    /**
     * Restart: bulk-deletes every study session of the learner and returns the row count (managed entities are not
     * detached).
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from JpathStudySession s where s.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
