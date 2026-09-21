package com.jlas.flashcard.repository;

import com.jlas.flashcard.entity.FlashcardSpeedRecallSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.*;

public interface FlashcardSpeedRecallSessionRepository extends JpaRepository<FlashcardSpeedRecallSession, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from FlashcardSpeedRecallSession s where s.id = :id")
    Optional<FlashcardSpeedRecallSession> lockById(@Param("id") Long id);
    Page<FlashcardSpeedRecallSession> findBySessionUserIdAndSessionSetIdOrderByCreatedAtDesc(Long userId, Long setId, Pageable page);
    @Query("select s.id from FlashcardSpeedRecallSession s where s.expiresAt <= :now and s.session.status = 'IN_PROGRESS'")
    List<Long> expiredIds(@Param("now") LocalDateTime now, Pageable page);
    @Query("select s from FlashcardSpeedRecallSession s where s.session.user.id=:userId and s.session.set.id=:setId and s.session.status='COMPLETED' and s.rulesVersion=:rules and s.contentVersion=:content and s.selectionFilter=:filter and s.totalQuestions=:count and s.autoSubmit=:auto order by s.score desc, s.totalResponseMs asc, s.id asc")
    List<FlashcardSpeedRecallSession> best(@Param("userId") Long userId, @Param("setId") Long setId,
        @Param("rules") String rules, @Param("content") String content, @Param("filter") String filter,
        @Param("count") int count, @Param("auto") boolean auto, Pageable page);
}
