package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathCheckSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Issued check sets. */
@Repository
public interface JpathCheckSetRepository extends JpaRepository<JpathCheckSet, Long> {

    /**
     * Restart: bulk-deletes every check set of the learner and returns the row count (managed entities are not
     * detached).
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from JpathCheckSet c where c.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
