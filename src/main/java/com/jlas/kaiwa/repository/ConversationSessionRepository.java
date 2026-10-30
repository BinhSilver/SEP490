package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.ConversationSession;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationSessionRepository extends JpaRepository<ConversationSession, Long> {

    @Query("""
        SELECT s FROM ConversationSession s
        JOIN FETCH s.scenario sc
        JOIN FETCH sc.category
        LEFT JOIN FETCH sc.aiRole
        LEFT JOIN FETCH sc.userRole
        LEFT JOIN FETCH sc.promptTemplate
        LEFT JOIN FETCH sc.parentScenario
        WHERE s.id = :id
        """)
    Optional<ConversationSession> findDetail(@Param("id") Long id);

    List<ConversationSession> findByUserIdAndScenario_IdOrderByStartedAtDescIdDesc(Long userId,Long scenarioId);

    @Query("""
        SELECT s FROM ConversationSession s JOIN FETCH s.scenario sc JOIN FETCH sc.category
        LEFT JOIN FETCH sc.parentScenario
        WHERE s.userId=:userId AND (:scenarioId IS NULL OR sc.id=:scenarioId OR sc.parentScenario.id=:scenarioId)
        ORDER BY s.startedAt DESC,s.id DESC
        """)
    List<ConversationSession> findUserHistory(@Param("userId") Long userId,@Param("scenarioId") Long scenarioId,Pageable page);
}
