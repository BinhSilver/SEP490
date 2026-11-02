package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.util.JpathPathLocks;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Learner paths. */
@Repository
public interface JpathUserPathRepository extends JpaRepository<JpathUserPath, Long> {

    boolean existsByUserIdAndStatus(Long userId, JpathPathStatus status);

    /** Newest path of the user in {@code status}. */
    Optional<JpathUserPath> findFirstByUserIdAndStatusOrderByIdDesc(Long userId, JpathPathStatus status);

    /**
     * The user's ACTIVE path locked for writing (SELECT … FOR UPDATE) until the transaction ends; at most one exists
     * (uk_jpath_path_active). A locking read returns the latest committed row, not the transaction's snapshot.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from JpathUserPath p where p.userId = :userId and p.status = :status")
    Optional<JpathUserPath> findForUpdate(@Param("userId") Long userId, @Param("status") JpathPathStatus status);

    /** {@link #findForUpdate} for the ACTIVE path; marks the transaction as holding the path lock ({@link JpathPathLocks}). */
    default Optional<JpathUserPath> findActiveForUpdate(Long userId) {
        Optional<JpathUserPath> path = findForUpdate(userId, JpathPathStatus.ACTIVE);
        path.ifPresent(p -> JpathPathLocks.markHeld());
        return path;
    }
}
