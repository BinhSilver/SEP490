package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathStudyEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Study events. */
@Repository
public interface JpathStudyEventRepository extends JpaRepository<JpathStudyEvent, Long> {

    /**
     * Restart: bulk-deletes every study event of the learner and returns the row count (managed entities are not
     * detached).
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from JpathStudyEvent e where e.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
