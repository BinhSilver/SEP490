package com.jlas.assessment.repository;

import com.jlas.assessment.entity.SmartTestSessionQuestion;
import com.jlas.assessment.entity.SmartTestSessionQuestion.SmartTestSessionQuestionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SmartTestSessionQuestionRepository
        extends JpaRepository<SmartTestSessionQuestion, SmartTestSessionQuestionId> {

    @Query("select sq from SmartTestSessionQuestion sq join fetch sq.question "
            + "where sq.session.attemptId = :attemptId order by sq.orderIndex")
    List<SmartTestSessionQuestion> findAllForAttempt(@Param("attemptId") Long attemptId);
}
