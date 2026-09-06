package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.entity.ConversationSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Repository cho {@link ConversationSession}. */
@Repository
public interface ConversationSessionRepository extends JpaRepository<ConversationSession, Long> {
    List<ConversationSession> findByUserId(Long userId);
}
