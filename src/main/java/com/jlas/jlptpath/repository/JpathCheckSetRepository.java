package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathCheckSet;
import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathTab;
import java.util.List;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * [JLAS-W0108] Issued check sets. Every lookup by token also takes the owner (plan decision 43): a token of another
 * learner reads exactly like an unknown one.
 */
@Repository
public interface JpathCheckSetRepository extends JpaRepository<JpathCheckSet, Long> {

    /**
     * Restart: bulk-deletes every check set of the learner and returns the row count (managed entities are not
     * detached).
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from JpathCheckSet c where c.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);

    /** The check set {@code token} when it belongs to {@code userId}. */
    Optional<JpathCheckSet> findByTokenAndUserId(String token, Long userId);

    /** The check set {@code token} of {@code userId}, locked for writing (SELECT … FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from JpathCheckSet c where c.token = :token and c.userId = :userId")
    Optional<JpathCheckSet> findByTokenAndUserIdForUpdate(@Param("token") String token, @Param("userId") Long userId);

    /** A check set of the session with this kind and status (e.g. the ISSUED one), if any. */
    Optional<JpathCheckSet> findFirstByUserIdAndSessionIdAndKindAndStatus(Long userId, Long sessionId,
                                                                         JpathCheckKind kind, String status);

    /** The check set of the session with this kind, tab and status (the step's ISSUED check), if any. */
    Optional<JpathCheckSet> findFirstByUserIdAndSessionIdAndKindAndTabAndStatusOrderByIdDesc(Long userId,
            Long sessionId, JpathCheckKind kind, JpathTab tab, String status);

    /** The learner's latest check sets of one kind and status, newest submit first (used questions, past mistakes). */
    List<JpathCheckSet> findTop10ByUserIdAndKindAndStatusOrderBySubmittedAtDescIdDesc(Long userId, JpathCheckKind kind,
                                                                                    String status);
}
