package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.ConversationSession;
import java.util.Optional;
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
        WHERE s.id = :id
        """)
    Optional<ConversationSession> findDetail(@Param("id") Long id);
}
