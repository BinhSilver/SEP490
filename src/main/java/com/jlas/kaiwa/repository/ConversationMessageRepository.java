package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.ConversationMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Transcript lưu bền trong conversation_message (JLAS-W0199). */
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

    @Query("select m from ConversationMessage m where m.session.id = :sessionId order by m.createdAt asc, m.id asc")
    List<ConversationMessage> findBySessionIdOrderByCreatedAtAsc(@Param("sessionId") Long sessionId);
}
