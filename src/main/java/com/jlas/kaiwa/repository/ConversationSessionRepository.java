package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.ConversationSession;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
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

    /** Sorted and paged on IDs only: sorting rows that fetch scenario JSON overflows MySQL's sort buffer. */
    @Query("""
        SELECT s.id FROM ConversationSession s JOIN s.scenario sc
        WHERE s.userId=:userId AND (:scenarioId IS NULL OR sc.id=:scenarioId OR sc.parentScenario.id=:scenarioId)
        ORDER BY s.startedAt DESC,s.id DESC
        """)
    List<Long> findUserHistoryIds(@Param("userId") Long userId,@Param("scenarioId") Long scenarioId,Pageable page);

    @Query("""
        SELECT s FROM ConversationSession s JOIN FETCH s.scenario sc JOIN FETCH sc.category
        LEFT JOIN FETCH sc.parentScenario
        WHERE s.id IN :ids
        """)
    List<ConversationSession> findWithScenarioByIdIn(@Param("ids") Collection<Long> ids);

    default List<ConversationSession> findUserHistory(Long userId, Long scenarioId, Pageable page) {
        List<Long> ids = findUserHistoryIds(userId, scenarioId, page);
        if (ids.isEmpty()) return List.of();
        Map<Long, ConversationSession> byId = findWithScenarioByIdIn(ids).stream()
                .collect(Collectors.toMap(ConversationSession::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }
}
