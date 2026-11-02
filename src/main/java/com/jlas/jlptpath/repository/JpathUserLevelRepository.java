package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserLevelId;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Levels inside learner paths. */
@Repository
public interface JpathUserLevelRepository extends JpaRepository<JpathUserLevel, JpathUserLevelId> {

    /** The level row locked for writing (SELECT … FOR UPDATE) until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from JpathUserLevel l where l.pathId = :pathId and l.level = :level")
    Optional<JpathUserLevel> findForUpdate(@Param("pathId") Long pathId, @Param("level") String level);
}
