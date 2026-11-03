package com.jlas.jlptpath.repository;

import java.util.List;
import java.util.Optional;
import com.jlas.jlptpath.entity.JpathStudySession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /** The session {@code id} when it belongs to {@code userId} (plan decision 43: never looked up without the owner). */
    Optional<JpathStudySession> findByIdAndUserId(Long id, Long userId);

    /** {@link #findByIdAndUserId} locked for writing (study events; taken after the path → level → plan → tabs locks). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from JpathStudySession s where s.id = :id and s.userId = :userId")
    Optional<JpathStudySession> findForUpdateByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** Sessions of the user in {@code status} and {@code mode}, newest first. */
    List<JpathStudySession> findByUserIdAndStatusAndModeOrderByIdDesc(Long userId, String status, String mode);
}
