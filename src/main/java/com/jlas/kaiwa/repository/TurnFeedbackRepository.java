package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.TurnFeedback;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Nhận xét từng lượt, lưu bền trong conversation_turn_feedback (JLAS-W0199). */
public interface TurnFeedbackRepository extends JpaRepository<TurnFeedback, Long> {

    @Query("select f from TurnFeedback f left join fetch f.message where f.session.id = :sessionId order by f.createdAt asc, f.id asc")
    List<TurnFeedback> findBySessionIdOrderByCreatedAtAsc(@Param("sessionId") Long sessionId);
}
